package com.yonagi.verse.resilience.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaygroundRateLimiterTest {
    @Test
    void membersShareModelWindowsWhileAnotherModelHasSeparateKeys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(String[].class))).thenReturn(0L);
        PlaygroundRateLimiter limiter = new PlaygroundRateLimiter(redis);
        limiter.check(2L, 9L);
        limiter.check(2L, 9L);
        limiter.check(2L, 10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, times(3)).execute(any(RedisScript.class), keys.capture(), any(String[].class));
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(2));
        assertTrue(keys.getAllValues().get(0).get(0).contains(":2:9:m:"));
    }

    @Test
    void minuteAndHourFailuresRemainDistinct() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(String[].class)))
                .thenReturn(1L, 2L);
        PlaygroundRateLimiter limiter = new PlaygroundRateLimiter(redis);
        var rpm = assertThrows(PlaygroundRateLimiter.LimitException.class,
                () -> limiter.check(2L, 9L));
        var rph = assertThrows(PlaygroundRateLimiter.LimitException.class,
                () -> limiter.check(2L, 9L));
        assertEquals("A001005", rpm.getErrorCode());
        assertEquals("PLAYGROUND_RPM", rpm.getReason());
        assertEquals("A001006", rph.getErrorCode());
        assertEquals("PLAYGROUND_RPH", rph.getReason());
        assertTrue(rpm.getRetryAfterSeconds() > 0);
    }
}
