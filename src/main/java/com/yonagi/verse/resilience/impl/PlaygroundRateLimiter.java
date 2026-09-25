package com.yonagi.verse.resilience.impl;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按租户和模型原子检查两个固定频率窗口。 */
@Component
@RequiredArgsConstructor
public class PlaygroundRateLimiter {
    public static final int RPM = 6;
    public static final int RPH = 120;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local minute = tonumber(redis.call('GET', KEYS[1]) or '0')
            local hour = tonumber(redis.call('GET', KEYS[2]) or '0')
            if minute >= tonumber(ARGV[1]) then return 1 end
            if hour >= tonumber(ARGV[2]) then return 2 end
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4]))
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public void check(Long tenantId, Long serviceId) {
        long second = System.currentTimeMillis() / 1000;
        long minute = second / 60;
        long hour = second / 3600;
        long minuteRetry = 60 - second % 60;
        long hourRetry = 3600 - second % 3600;
        String prefix = "verse:playground:limit:" + tenantId + ":" + serviceId + ":";
        Long result = redis.execute(SCRIPT,
                List.of(prefix + "m:" + minute, prefix + "h:" + hour),
                String.valueOf(RPM), String.valueOf(RPH),
                String.valueOf(minuteRetry + 1), String.valueOf(hourRetry + 1));
        if (Long.valueOf(1).equals(result)) throw new LimitException(PlaygroundErrorCodeEnum.RPM_LIMIT,
                "PLAYGROUND_RPM", minuteRetry);
        if (Long.valueOf(2).equals(result)) throw new LimitException(PlaygroundErrorCodeEnum.RPH_LIMIT,
                "PLAYGROUND_RPH", hourRetry);
        if (!Long.valueOf(0).equals(result)) throw new IllegalStateException("PlayGround 限流检查失败");
    }

    @Getter
    public static class LimitException extends ClientException {
        private final String reason;
        private final Long retryAfterSeconds;

        public LimitException(PlaygroundErrorCodeEnum code, String reason, Long retryAfterSeconds) {
            super(code);
            this.reason = reason;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
