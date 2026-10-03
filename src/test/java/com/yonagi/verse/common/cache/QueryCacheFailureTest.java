package com.yonagi.verse.common.cache;

import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ServerException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QueryCacheFailureTest {
    @Test void redisUnavailableNeverCallsLoader() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new RedisConnectionFailureException("offline"));
        RedissonClient locks = mock(RedissonClient.class);
        QueryCache cache = new QueryCache(redis, locks, new QueryCacheProperties(), new SimpleMeterRegistry());
        AtomicInteger loads = new AtomicInteger();
        assertThrows(ServerException.class, () -> cache.read("query", RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY, "id", String.class, List.of("t_test"), 10000,
                () -> { loads.incrementAndGet(); return "value"; }));
        assertEquals(0, loads.get());
        verifyNoInteractions(locks);
    }
}
