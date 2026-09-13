package com.yonagi.verse.resilience.impl;

import com.yonagi.verse.resilience.api.RateLimitContext;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    @Test
    void invalidatingTenantRpmDeletesOnlyTenantLimiter() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter("verse:ratelimit:rpm:tenant:20")).thenReturn(limiter);
        RedisRateLimiter rateLimiter = new RedisRateLimiter(redissonClient, mock(StringRedisTemplate.class));

        rateLimiter.invalidateTenantRpm(20L);

        verify(limiter).delete();
    }

    @Test
    void nextCheckRebuildsTenantLimiterWithLatestRpm() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter("verse:ratelimit:rpm:tenant:20")).thenReturn(limiter);
        when(limiter.tryAcquire()).thenReturn(true);
        RedisRateLimiter rateLimiter = new RedisRateLimiter(redissonClient, mock(StringRedisTemplate.class));

        rateLimiter.invalidateTenantRpm(20L);
        rateLimiter.check(RateLimitContext.builder().tenantId(20L).tenantRpm(1200).build());

        verify(limiter).delete();
        verify(limiter).trySetRate(RateType.OVERALL, 1200, 1, RateIntervalUnit.MINUTES);
        verify(limiter).tryAcquire();
    }
}
