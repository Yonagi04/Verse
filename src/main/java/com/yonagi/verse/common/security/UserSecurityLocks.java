package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.ExternalAuthErrorCodeEnum;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 用户级安全锁覆盖提交窗口，比单个会话锁更严格；Redis看门狗延长持锁期。 */
@Component
@RequiredArgsConstructor
public class UserSecurityLocks {
    private final RedissonClient redisson;
    public <T> T withUser(Long userId, Supplier<T> action) {
        var lock = redisson.getLock("verse:user-security:" + userId);
        try {
            if (!lock.tryLock(3, TimeUnit.SECONDS)) throw new ClientException(ExternalAuthErrorCodeEnum.FLOW_IN_PROGRESS);
            try { return action.get(); } finally { lock.unlock(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException(ExternalAuthErrorCodeEnum.FLOW_IN_PROGRESS);
        }
    }
}
