package com.yonagi.verse.service.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.CostReconciliationErrorCode;
import com.yonagi.verse.service.LoginSessionValidationService;
import com.yonagi.verse.service.LoginSessionValidationService.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 通过公开用例验证交接语义，存储原子性另由受控 Redis 验证。 */
class CostReconciliationSessionServiceTest {
    private static final String BATCH = "VCGkO9s2";
    private static final String OTHER = "VCGkO9S2";
    private static final String VERIFIER = "v".repeat(43);
    private final LoginSessionValidationService logins = mock(LoginSessionValidationService.class);
    private final RRateLimiter limiter = mock(RRateLimiter.class);
    private final Map<String, String> records = new HashMap<>();
    private final Identity parent = new Identity(42L, "member", "session", "parent-hash",
            System.currentTimeMillis() + 120_000L);
    private ValueOperations<String, String> values;
    private CostReconciliationSessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedissonClient redisson = mock(RedissonClient.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire()).thenReturn(true);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation ->
                records.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
        when(values.getAndDelete(anyString())).thenAnswer(invocation -> records.remove(invocation.getArgument(0)));
        when(values.get(anyString())).thenAnswer(invocation -> records.get(invocation.getArgument(0)));
        service = new CostReconciliationSessionService(redis, redisson, logins, new ObjectMapper());
    }

    @Test
    void wrongProofAndBatchDoNotConsumeHandoffAndReplayCannotIssueAnotherCredential() throws Exception {
        String code = issue();
        assertEquals(CostReconciliationErrorCode.HANDOFF_INVALID.code(), assertThrows(ClientException.class,
                () -> service.exchange(BATCH, code, "a".repeat(43))).getErrorCode());
        assertEquals(CostReconciliationErrorCode.HANDOFF_INVALID.code(), assertThrows(ClientException.class,
                () -> service.exchange(OTHER, code, VERIFIER)).getErrorCode());

        var exchange = service.exchange(BATCH, code, VERIFIER);
        assertEquals(parent.expiresAtMillis(), exchange.expiresAt());
        assertTrue(exchange.token().startsWith("crs_"));
        assertFalse(exchange.toString().contains(exchange.token()));
        assertEquals(parent, service.authenticate(exchange.token(), BATCH));
        assertEquals(CostReconciliationErrorCode.HANDOFF_INVALID.code(), assertThrows(ClientException.class,
                () -> service.exchange(BATCH, code, VERIFIER)).getErrorCode());
        assertEquals(1, records.size());
        assertTrue(records.keySet().stream().noneMatch(key -> key.contains(exchange.token())));
    }

    @Test
    void scopeIsCaseSensitiveAndParentRevocationAppliesToAnExistingCredential() throws Exception {
        var exchange = service.exchange(BATCH, issue(), VERIFIER);
        assertEquals(BaseErrorCode.TOKEN_INVALID.code(), assertThrows(ClientException.class,
                () -> service.authenticate(exchange.token(), OTHER)).getErrorCode());
        assertEquals(BaseErrorCode.TOKEN_INVALID.code(), assertThrows(ClientException.class,
                () -> service.authenticate(exchange.token(), null)).getErrorCode());
        assertEquals(BaseErrorCode.TOKEN_INVALID.code(), assertThrows(ClientException.class,
                () -> service.authenticate("jwt", BATCH)).getErrorCode());
        doThrow(new ClientException(BaseErrorCode.TOKEN_INVALID)).when(logins).requireActive(parent);
        assertEquals(BaseErrorCode.TOKEN_INVALID.code(), assertThrows(ClientException.class,
                () -> service.authenticate(exchange.token(), BATCH)).getErrorCode());
        records.clear();
        assertEquals(BaseErrorCode.TOKEN_EXPIRED.code(), assertThrows(ClientException.class,
                () -> service.authenticate(exchange.token(), BATCH)).getErrorCode());
    }

    @Test
    void revokedOrExpiredParentCannotCreateSession() throws Exception {
        String code = issue();
        doThrow(new ClientException(BaseErrorCode.TOKEN_INVALID)).when(logins).requireActive(parent);
        assertThrows(ClientException.class, () -> service.exchange(BATCH, code, VERIFIER));
        assertTrue(records.isEmpty());

        var expired = new Identity(42L, "member", "session", "parent-hash", 1L);
        String expiredCode = service.issue(expired, BATCH, challenge());
        assertEquals(BaseErrorCode.TOKEN_EXPIRED.code(), assertThrows(ClientException.class,
                () -> service.exchange(BATCH, expiredCode, VERIFIER)).getErrorCode());
        assertTrue(records.isEmpty());
    }

    @Test
    void redisWriteFailureAfterConsumptionCannotReplayTheSameHandoff() throws Exception {
        String code = issue();
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new DataAccessResourceFailureException("fixture unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.exchange(BATCH, code, VERIFIER));
        assertTrue(records.isEmpty());
        assertEquals(CostReconciliationErrorCode.HANDOFF_INVALID.code(), assertThrows(ClientException.class,
                () -> service.exchange(BATCH, code, VERIFIER)).getErrorCode());
    }

    @Test
    void rejectsInvalidInputAndLimitBeforeWritingHandoff() throws Exception {
        assertThrows(ClientException.class, () -> service.issue(parent, "../test", "x"));
        assertThrows(ClientException.class, () -> service.exchange(BATCH, "malformed", VERIFIER));
        when(limiter.tryAcquire()).thenReturn(false);
        assertEquals(CostReconciliationErrorCode.HANDOFF_RATE_LIMITED.code(), assertThrows(ClientException.class,
                this::issue).getErrorCode());
        assertTrue(records.isEmpty());
    }

    @Test
    void rejectsCollisionAndCorruptStoredIdentity() throws Exception {
        String code = issue();
        records.replaceAll((key, value) -> "broken");
        assertThrows(ServerException.class, () -> service.exchange(BATCH, code, VERIFIER));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThrows(ServerException.class, this::issue);
    }

    private String issue() throws Exception {
        return service.issue(parent, BATCH, challenge());
    }

    private static String challenge() throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    }
}
