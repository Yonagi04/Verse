package com.yonagi.verse.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.service.messaging.VerificationCodeStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetCredentialStoreTest {
    @Test void unavailableOrUncertainRedisCannotAuthorizeConsumption() {
        for (Long result : new Long[]{null, -1L, 2L}) {
            var redis = mock(StringRedisTemplate.class);
            when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                    .thenReturn(result);
            var failure = assertThrows(ServerException.class,
                    () -> new PasswordResetCredentialStore(redis).consume("phone-hash", DigestUtil.md5Hex("token")));
            assertEquals(UserErrorCodeEnum.USER_RESET_CREDENTIAL_STATE_ERROR.code(), failure.getErrorCode());
        }
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis disconnected"));
        assertThrows(ServerException.class, () -> new PasswordResetCredentialStore(redis).consume("phone-hash", DigestUtil.md5Hex("token")));
    }

    @Test void unavailableVerificationStateNeverReturnsIssuedCredential() {
        for (Long result : new Long[]{null, -1L, 2L}) {
            var redis = mock(StringRedisTemplate.class);
            when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                    .thenReturn(result);
            assertThrows(ServerException.class, () -> new VerificationCodeStore(redis)
                    .verifyPasswordResetCode("phone-hash", "123456", DigestUtil.md5Hex("token")));
        }
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis disconnected"));
        assertThrows(ServerException.class, () -> new VerificationCodeStore(redis)
                .verifyPasswordResetCode("phone-hash", "123456", DigestUtil.md5Hex("token")));
    }

    @Test void rejectedCredentialsKeepExistingBusinessErrors() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class))).thenReturn(0L);
        assertEquals(UserErrorCodeEnum.USER_RESET_PASSWORD_FAIL.code(), assertThrows(ClientException.class,
                () -> new PasswordResetCredentialStore(redis).consume("phone-hash", DigestUtil.md5Hex("token"))).getErrorCode());
        assertEquals(UserErrorCodeEnum.USER_PHONE_CODE_ERROR.code(), assertThrows(ClientException.class,
                () -> new VerificationCodeStore(redis).verifyPasswordResetCode("phone-hash", "123456", DigestUtil.md5Hex("token"))).getErrorCode());
    }
}
