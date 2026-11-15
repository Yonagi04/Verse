package com.yonagi.verse.service.budget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.CostReconciliationErrorCode;
import com.yonagi.verse.service.LoginSessionValidationService;
import com.yonagi.verse.service.LoginSessionValidationService.Identity;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/** 管理补账专用短期凭证，并以既有父会话为撤销权威。 */
@Service
@RequiredArgsConstructor
public class CostReconciliationSessionService {
    private static final String CREDENTIAL_PREFIX = "crs_";
    private static final Pattern BATCH_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9]{7}");
    private static final Pattern PROOF = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern SCOPED_CREDENTIAL = Pattern.compile(CREDENTIAL_PREFIX + PROOF.pattern());
    private static final Duration HANDOFF_TTL = Duration.ofMinutes(1);
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration LIMIT_TTL = Duration.ofMinutes(2);
    private static final int ISSUES_PER_MINUTE = 8;
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final LoginSessionValidationService loginSessions;
    private final ObjectMapper json;

    /** 签发绑定批次与当前浏览器挑战的一次性码，调用方必须已验证主站会话。 */
    public String issue(Identity parent, String batchCode, String challenge) {
        requireInput(batchCode, challenge);
        var limiter = redisson.getRateLimiter(RedisKeyConstant.RECONCILIATION_HANDOFF_LIMIT_KEY + parent.userId());
        limiter.trySetRate(RateType.OVERALL, ISSUES_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
        limiter.expire(LIMIT_TTL);
        if (!limiter.tryAcquire()) {
            throw new ClientException(CostReconciliationErrorCode.HANDOFF_RATE_LIMITED);
        }
        String code = randomProof();
        store(handoffKey(batchCode, code, challenge), new ScopedIdentity(batchCode, parent), HANDOFF_TTL);
        return code;
    }

    /** 以发起页 verifier 原子兑换一次性码，重新检查父会话并签发批次凭证。 */
    public Exchange exchange(String batchCode, String code, String verifier) {
        requireInput(batchCode, code);
        requireInput(batchCode, verifier);
        String value = redis.opsForValue().getAndDelete(handoffKey(batchCode, code, digest(verifier)));
        if (value == null) {
            throw new ClientException(CostReconciliationErrorCode.HANDOFF_INVALID);
        }
        ScopedIdentity scope = decode(value);
        loginSessions.requireActive(scope.parent());
        long now = System.currentTimeMillis();
        long expiresAt = Math.min(scope.parent().expiresAtMillis(), now + SESSION_TTL.toMillis());
        if (expiresAt <= now) {
            throw new ClientException(BaseErrorCode.TOKEN_EXPIRED);
        }
        String token = CREDENTIAL_PREFIX + randomProof();
        store(sessionKey(token), scope, Duration.ofMillis(expiresAt - now));
        return new Exchange(token, expiresAt);
    }

    /** 校验凭证、目标批次和父会话，返回原账号身份供既有业务权限校验。 */
    public Identity authenticate(String token, String batchCode) {
        if (batchCode == null || token == null || !SCOPED_CREDENTIAL.matcher(token).matches()) {
            throw new ClientException(BaseErrorCode.TOKEN_INVALID);
        }
        String value = redis.opsForValue().get(sessionKey(token));
        if (value == null) {
            throw new ClientException(BaseErrorCode.TOKEN_EXPIRED);
        }
        ScopedIdentity scope = decode(value);
        if (!batchCode.equals(scope.batchCode())) {
            throw new ClientException(BaseErrorCode.TOKEN_INVALID);
        }
        loginSessions.requireActive(scope.parent());
        return scope.parent();
    }

    /** 区分不透明批次凭证与正常登录 JWT。 */
    public static boolean isScopedCredential(String token) {
        return token.startsWith(CREDENTIAL_PREFIX);
    }

    private static void requireInput(String batchCode, String proof) {
        if (batchCode == null || !BATCH_CODE.matcher(batchCode).matches()
                || proof == null || !PROOF.matcher(proof).matches()) {
            throw new ClientException(BaseErrorCode.CLIENT_ERROR);
        }
    }

    private void store(String key, ScopedIdentity identity, Duration ttl) {
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, json.writeValueAsString(identity), ttl))) {
                throw new ServerException(BaseErrorCode.SERVICE_ERROR);
            }
        } catch (JsonProcessingException exception) {
            throw new ServerException(null, exception, BaseErrorCode.SERVICE_ERROR);
        }
    }

    private ScopedIdentity decode(String value) {
        try {
            return json.readValue(value, ScopedIdentity.class);
        } catch (JsonProcessingException exception) {
            throw new ServerException(null, exception, BaseErrorCode.SERVICE_ERROR);
        }
    }

    private static String handoffKey(String batchCode, String code, String challenge) {
        return RedisKeyConstant.RECONCILIATION_HANDOFF_KEY + digest(code) + ":" + batchCode + ":" + challenge;
    }

    private static String sessionKey(String token) {
        return RedisKeyConstant.RECONCILIATION_SESSION_KEY + digest(token);
    }

    private static String randomProof() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return BASE64.encodeToString(bytes);
    }

    private static String digest(String proof) {
        try {
            return BASE64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(proof.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record ScopedIdentity(String batchCode, Identity parent) {
    }

    /** 兑换结果的凭证仅用于当前补账页面。 */
    public record Exchange(
            /** 批次范围的短期凭证。 */ String token,
            /** 凭证的毫秒级到期时间。 */ long expiresAt) {
        @Override
        public String toString() {
            return "Exchange[expiresAt=" + expiresAt + "]";
        }
    }
}
