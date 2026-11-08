package com.yonagi.verse.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

class LoginSessionValidationServiceTest {
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> tokens = mock(ValueOperations.class);
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final Claims claims = mock(Claims.class);
    private final LoginSessionValidationService sessions = new LoginSessionValidationService(jwt, redis, accounts);
    private final String tokenHash = DigestUtil.md5Hex("token");
    private final String tokenKey = RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash;

    @BeforeEach
    void activeSession() {
        when(jwt.parseToken("token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("10");
        when(claims.get("username", String.class)).thenReturn("alice");
        when(claims.getId()).thenReturn("session-1");
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60_000));
        when(redis.opsForValue()).thenReturn(tokens);
        when(tokens.get(tokenKey)).thenReturn("10");
        when(accounts.isAccountActive(10L)).thenReturn(true);
    }

    @Test
    void loginIdentityContainsOnlyVerifiedMetadata() {
        LoginSessionValidationService.Identity identity = sessions.authenticate("token");
        assertEquals(10L, identity.userId());
        assertEquals("alice", identity.username());
        assertEquals("session-1", identity.sessionId());
        assertEquals(tokenHash, identity.tokenHash());
        assertEquals(claims.getExpiration().getTime(), identity.expiresAtMillis());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"11"})
    void revokedOrForeignIndexCannotAuthenticate(String indexedUser) {
        when(tokens.get(tokenKey)).thenReturn(indexedUser);
        assertInvalid();
        verifyNoInteractions(accounts);
    }

    @Test
    void accountIsRecheckedAfterAnEarlierSuccessfulAuthentication() {
        LoginSessionValidationService.Identity identity = sessions.authenticate("token");
        when(accounts.isAccountActive(10L)).thenReturn(false);
        assertEquals(BaseErrorCode.TOKEN_INVALID.code(),
                assertThrows(ClientException.class, () -> sessions.requireActive(identity)).getErrorCode());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"phone", "0", "-1", "+10", "010", "9223372036854775808"})
    void malformedSubjectCannotBecomeUserIdentity(String subject) {
        when(claims.getSubject()).thenReturn(subject);
        assertInvalid();
        verifyNoInteractions(tokens, accounts);
    }

    @Test
    void resetPasswordAndManagementTokensAreRejectedDespiteValidSignature() {
        when(claims.get("username", String.class)).thenReturn(null);
        assertInvalid();
        when(claims.get("username", String.class)).thenReturn("alice");
        when(claims.getId()).thenReturn(null);
        assertInvalid();
        when(claims.getId()).thenReturn("session-1");
        when(claims.getExpiration()).thenReturn(null);
        assertInvalid();
        verifyNoInteractions(tokens, accounts);
    }

    @Test
    void missingExpirationAndExpiredIdentityCannotAccessDependencies() {
        when(claims.getExpiration()).thenReturn(new Date(0));
        assertEquals(BaseErrorCode.TOKEN_EXPIRED.code(),
                assertThrows(ClientException.class, () -> sessions.authenticate("token")).getErrorCode());
        verifyNoInteractions(tokens, accounts);
    }

    @Test
    void dependencyFailureCannotProduceAnAuthenticatedIdentity() {
        IllegalStateException unavailable = new IllegalStateException("session store unavailable");
        when(tokens.get(tokenKey)).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> sessions.authenticate("token")));
        verifyNoInteractions(accounts);
    }

    @Test
    void parsingFailuresExposeOnlySafeAuthenticationErrors() {
        when(jwt.parseToken("token")).thenThrow(new JwtException("private token fragment"));
        ClientException invalid = assertThrows(ClientException.class, () -> sessions.authenticate("token"));
        assertEquals(BaseErrorCode.TOKEN_INVALID.message(), invalid.getErrorMessage());
        doThrow(new IllegalArgumentException("private input")).when(jwt).parseToken("token");
        assertInvalid();
        doThrow(new ExpiredJwtException(null, claims, "private token fragment")).when(jwt).parseToken("token");
        ClientException expired = assertThrows(ClientException.class, () -> sessions.authenticate("token"));
        assertEquals(BaseErrorCode.TOKEN_EXPIRED.code(), expired.getErrorCode());
        assertEquals(BaseErrorCode.TOKEN_EXPIRED.message(), expired.getErrorMessage());
        verifyNoInteractions(tokens, accounts);
    }

    @Test
    void wronglyTypedUsernameClaimCannotLeakParserDiagnostics() {
        when(claims.get("username", String.class)).thenThrow(new JwtException("private claim"));
        ClientException invalid = assertThrows(ClientException.class, () -> sessions.authenticate("token"));
        assertEquals(BaseErrorCode.TOKEN_INVALID.message(), invalid.getErrorMessage());
        verifyNoInteractions(tokens, accounts);
    }

    private void assertInvalid() {
        assertEquals(BaseErrorCode.TOKEN_INVALID.code(),
                assertThrows(ClientException.class, () -> sessions.authenticate("token")).getErrorCode());
    }
}
