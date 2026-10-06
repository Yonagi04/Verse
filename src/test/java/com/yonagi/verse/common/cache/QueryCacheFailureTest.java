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
    @Test void negativeCacheStillRunsCurrentAuthorization() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(List.of("v1"));
        var values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{\"errorCode\":\"B000200\",\"errorMessage\":\"old denial\"}");
        QueryCache cache = new QueryCache(redis, mock(RedissonClient.class), new QueryCacheProperties(), new SimpleMeterRegistry());
        var current = new com.yonagi.verse.common.convention.exception.ClientException("current denial");
        assertSame(current, assertThrows(com.yonagi.verse.common.convention.exception.ClientException.class,
                () -> cache.get("private", "test:", "id", String.class, List.of("t_test"), 10000,
                        () -> { throw current; }, () -> { fail("must not load"); return null; }, value -> true)));
    }
    @Test void redisUnavailableUsesBoundedDatabaseFallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new RedisConnectionFailureException("offline"));
        RedissonClient locks = mock(RedissonClient.class);
        QueryCache cache = new QueryCache(redis, locks, new QueryCacheProperties(), new SimpleMeterRegistry());
        AtomicInteger loads = new AtomicInteger();
        assertEquals("value", cache.read("query", RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY, "id", String.class, List.of("t_test"), 10000,
                () -> { loads.incrementAndGet(); return "value"; }));
        assertEquals(1, loads.get());
        verifyNoInteractions(locks);
    }

    @Test void databaseFailureKeepsCauseAndIsNotRetriedAsRedisFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new RedisConnectionFailureException("offline"));
        QueryCache cache = new QueryCache(redis, mock(RedissonClient.class), new QueryCacheProperties(), new SimpleMeterRegistry());
        var sql = new org.springframework.jdbc.BadSqlGrammarException("key-list", "SELECT cost_config_version",
                new java.sql.SQLException("Unknown column cost_config_version", "42S22", 1054));
        AtomicInteger loads = new AtomicInteger();
        ServerException error = assertThrows(ServerException.class, () -> cache.read("query", RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY,
                "id", String.class, List.of("t_test"), 10000, () -> { loads.incrementAndGet(); throw sql; }));
        assertSame(sql, error.getCause());
        assertEquals("数据查询失败，请稍后重试", error.getErrorMessage());
        assertEquals(1, loads.get());
    }

    @Test void fallbackRechecksPermissionBeforeLoadingPrivateResults() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new RedisConnectionFailureException("offline"));
        QueryCache cache = new QueryCache(redis, mock(RedissonClient.class), new QueryCacheProperties(), new SimpleMeterRegistry());
        AtomicInteger loads = new AtomicInteger();
        var denied = new com.yonagi.verse.common.convention.exception.ClientException("permission denied");
        assertSame(denied, assertThrows(com.yonagi.verse.common.convention.exception.ClientException.class,
                () -> cache.get("private", RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY, "id", String.class, List.of("t_test"),
                        10000, () -> { throw denied; }, () -> { loads.incrementAndGet(); return "private"; }, value -> true)));
        assertEquals(0, loads.get());
    }

    @Test void redisOutageDoesNotRemoveDatabaseConcurrencyLimit() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new RedisConnectionFailureException("offline"));
        QueryCacheProperties properties = new QueryCacheProperties(); properties.setMaxConcurrency(1);
        QueryCache cache = new QueryCache(redis, mock(RedissonClient.class), properties, new SimpleMeterRegistry());
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var active = executor.submit(() -> cache.read("query", RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY, "id", String.class,
                    List.of("t_test"), 10000, () -> {
                        entered.countDown();
                        try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                        catch (InterruptedException error) { throw new RuntimeException(error); }
                        return "value";
                    }));
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(ServerException.class, () -> cache.read("query", RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY, "other", String.class,
                        List.of("t_test"), 10000, () -> { fail("不能无限回源"); return "other"; }));
            } finally { release.countDown(); }
            assertEquals("value", active.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("recovered", cache.read("query", RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY, "id", String.class,
                    List.of("t_test"), 10000, () -> "recovered"), "失败和成功路径均释放回源许可");
        }
    }
}
