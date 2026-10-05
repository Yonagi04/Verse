package com.yonagi.verse.service.messaging;

import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.MessagingErrorCode;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

/** 冷却、旧状态快照和新验证码作为一次 Redis 原子预留。 */
@Service
public class VerificationCodeStore {
    private static final DefaultRedisScript<Long> RESERVE = script("messaging/sms/reserve-code.lua");
    private static final DefaultRedisScript<Long> RESTORE = script("messaging/sms/restore-code.lua");
    private static final DefaultRedisScript<Long> CURRENT = script("messaging/sms/current-code.lua");
    private static final DefaultRedisScript<Long> VERIFY_RESET = script("messaging/sms/verify-reset-code.lua");
    private static final String OWNER_SUFFIX = ":owner";
    private static final String BACKUP_SUFFIX = ":backup:";
    private final StringRedisTemplate redis;
    public VerificationCodeStore(StringRedisTemplate redis) { this.redis = redis; }

    private static DefaultRedisScript<Long> script(String location) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource(location)); script.setResultType(Long.class);
        return script;
    }

    /** 验证码消费和重置凭证签发必须一起完成，不能留下重复签发或丢失凭证的窗口。 */
    public void verifyPasswordResetCode(String phoneHash, String code, String tokenHash) {
        String codeKey = RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + phoneHash;
        Long result;
        try {
            result = redis.execute(VERIFY_RESET, List.of(codeKey, codeKey + OWNER_SUFFIX,
                    RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + phoneHash), code, tokenHash);
        } catch (RuntimeException failure) {
            throw new ServerException(UserErrorCodeEnum.USER_RESET_CREDENTIAL_STATE_ERROR);
        }
        if (Long.valueOf(0).equals(result)) throw new ClientException(UserErrorCodeEnum.USER_PHONE_CODE_ERROR);
        if (!Long.valueOf(1).equals(result)) throw new ServerException(UserErrorCodeEnum.USER_RESET_CREDENTIAL_STATE_ERROR);
    }

    public Reservation reserve(String codeKey, String rateKey, String phoneHash, String code, String requestId, int dailyLimit) {
        String backup = codeKey + BACKUP_SUFFIX + requestId;
        String owner = codeKey + OWNER_SUFFIX;
        Long result;
        try {
            result = redis.execute(RESERVE, List.of(codeKey, rateKey, backup,
                    RedisKeyConstant.VERIFICATION_SMS_DAILY_KEY + phoneHash, owner),
                    requestId, code, "60000", "300000", Integer.toString(dailyLimit));
        } catch (RuntimeException failure) {
            throw new MessageSendException(MessagingErrorCode.STATE_FAILED, false);
        }
        if (Long.valueOf(0).equals(result)) throw new ClientException(UserErrorCodeEnum.USER_PHONE_CODE_SEND_FREQUENT);
        if (Long.valueOf(-1).equals(result)) throw new ClientException(MessagingErrorCode.DAILY_LIMIT);
        if (!Long.valueOf(1).equals(result)) throw new MessageSendException(MessagingErrorCode.STATE_FAILED, false);
        return new Reservation(codeKey, rateKey, backup, owner, code, requestId);
    }

    public void restore(Reservation r) {
        try {
            Long result = redis.execute(RESTORE, List.of(r.codeKey(), r.rateKey(), r.backupKey(), r.ownerKey()), r.requestId(), r.code());
            if (!Long.valueOf(0).equals(result) && !Long.valueOf(1).equals(result)) {
                throw new IllegalStateException("Invalid restore result");
            }
        } catch (RuntimeException failure) {
            throw new MessageSendException(MessagingErrorCode.STATE_FAILED, false);
        }
    }
    /** 排队期间可能被验证消费或新请求替换；每次外发前必须重新核验。 */
    public boolean isCurrent(Reservation r) {
        try {
            Long result = redis.execute(CURRENT, List.of(r.codeKey(), r.ownerKey()), r.code(), r.requestId());
            if (result == null) throw new IllegalStateException("No verification result");
            return result > 0;
        } catch (RuntimeException failure) {
            throw new MessageSendException(MessagingErrorCode.STATE_FAILED, false);
        }
    }
    public record Reservation(String codeKey, String rateKey, String backupKey, String ownerKey, String code, String requestId) {
        @Override public String toString() { return "VerificationReservation[requestId=" + requestId + "]"; }
    }
}
