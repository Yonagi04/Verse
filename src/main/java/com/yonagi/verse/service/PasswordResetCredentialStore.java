package com.yonagi.verse.service;

import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/** 重置凭证是授权状态而非查询缓存；消费结果不确定时禁止继续更新密码或恢复凭证。 */
@Service
public class PasswordResetCredentialStore {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>();
    static {
        CONSUME.setLocation(new ClassPathResource("security/consume-reset-token.lua"));
        CONSUME.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;

    public PasswordResetCredentialStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void consume(String phoneHash, String tokenHash) {
        Long result;
        try {
            result = redis.execute(CONSUME, List.of(RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + phoneHash), tokenHash);
        } catch (RuntimeException failure) {
            throw new ServerException(UserErrorCodeEnum.USER_RESET_CREDENTIAL_STATE_ERROR);
        }
        if (Long.valueOf(0).equals(result)) throw new ClientException(UserErrorCodeEnum.USER_RESET_PASSWORD_FAIL);
        if (!Long.valueOf(1).equals(result)) throw new ServerException(UserErrorCodeEnum.USER_RESET_CREDENTIAL_STATE_ERROR);
    }
}
