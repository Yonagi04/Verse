package com.yonagi.verse.service.messaging;

import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.service.PasswordResetCredentialStore;
import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只使用随机独立键；显式连接隔离的测试 Redis，不 flush 共享数据库。 */
@EnabledIfSystemProperty(named = "messaging.redis-it", matches = "true")
class VerificationCodeRedisIntegrationTest {
    private static RedissonClient client;
    private static RedissonConnectionFactory factory;
    private static StringRedisTemplate redis;
    private VerificationCodeStore codes;
    private String prefix;
    @BeforeAll static void connect() throws Exception {
        var config = new Config(); config.setThreads(2); config.setNettyThreads(2);
        config.useSingleServer().setAddress(System.getProperty("messaging.redis-address", "redis://127.0.0.1:6379"))
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        client = Redisson.create(config); factory = new RedissonConnectionFactory(client); factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }
    @AfterAll static void close() throws Exception { if (client != null) client.shutdown(); if (factory != null) factory.destroy(); }
    @BeforeEach void setup() { prefix = "verse:messaging-it:" + UUID.randomUUID(); codes = new VerificationCodeStore(redis); }
    @AfterEach void clear() {
        var keys = redis.keys(prefix + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        redis.delete(RedisKeyConstant.VERIFICATION_SMS_DAILY_KEY + prefix);
        redis.delete(RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + prefix);
    }
    @Test void concurrentReservationHasOneWinnerAndSharedPhoneLimit() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Boolean>>(); var start = new CountDownLatch(1);
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await();
                try { codes.reserve(prefix + ":code", prefix + ":rate", prefix, "123456", UUID.randomUUID().toString(), 10); return true; }
                catch (ClientException rejection) { return false; }
            }));
            start.countDown(); int accepted = 0; for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
        }
        assertEquals("1", redis.opsForValue().get(RedisKeyConstant.VERIFICATION_SMS_DAILY_KEY + prefix));
        assertThrows(ClientException.class, () -> codes.reserve(prefix + ":closure", prefix + ":closure-rate", prefix, "654321", "other", 1));
    }
    @Test void rejectionRestoresRemainingTtlAndOldCallbackCannotClobberNewRequest() {
        redis.opsForValue().set(prefix + ":code", "old", 120, TimeUnit.SECONDS);
        var first = codes.reserve(prefix + ":code", prefix + ":rate", prefix, "111111", "first", 10);
        codes.restore(first);
        assertEquals("old", redis.opsForValue().get(prefix + ":code"));
        assertTrue(redis.getExpire(prefix + ":code", TimeUnit.SECONDS) <= 120);
        assertTrue(redis.getExpire(prefix + ":code", TimeUnit.SECONDS) > 0);
        var second = codes.reserve(prefix + ":code", prefix + ":rate", prefix, "222222", "second", 10);
        redis.delete(prefix + ":rate");
        codes.reserve(prefix + ":code", prefix + ":rate", prefix, "333333", "third", 10);
        codes.restore(second);
        assertEquals("333333", redis.opsForValue().get(prefix + ":code")); assertEquals("third", redis.opsForValue().get(prefix + ":rate"));
    }
    @Test void rejectsBadRedisTypesBeforePartialWritesAndNeverRestoresConsumedCode() {
        redis.opsForList().leftPush(prefix + ":code", "bad");
        assertThrows(MessageSendException.class, () -> codes.reserve(prefix + ":code", prefix + ":rate", prefix, "111111", "first", 10));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(prefix + ":rate"))); redis.delete(prefix + ":code");
        redis.opsForValue().set(RedisKeyConstant.VERIFICATION_SMS_DAILY_KEY + prefix, "1.5");
        assertThrows(MessageSendException.class, () -> codes.reserve(prefix + ":code", prefix + ":rate", prefix, "111111", "first", 10));
        assertNull(redis.opsForValue().get(prefix + ":code"));
        redis.delete(RedisKeyConstant.VERIFICATION_SMS_DAILY_KEY + prefix);
        var reservation = codes.reserve(prefix + ":code", prefix + ":rate", prefix, "111111", "first", 10);
        assertTrue(codes.isCurrent(reservation));
        redis.delete(prefix + ":code"); assertFalse(codes.isCurrent(reservation)); codes.restore(reservation);
        assertNull(redis.opsForValue().get(prefix + ":code"));
    }

    @Test void concurrentTokenConsumptionHasOneWinnerWithoutUserLock() throws Exception {
        String tokenHash = DigestUtil.md5Hex("reset-token");
        redis.opsForValue().set(RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + prefix, tokenHash, 10, TimeUnit.MINUTES);
        var credentials = new PasswordResetCredentialStore(redis);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<Boolean>>();
            for (int n = 0; n < 8; n++) futures.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { credentials.consume(prefix, tokenHash); return true; }
                catch (ClientException rejected) { return false; }
            }));
            start.countDown(); int consumed = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) consumed++;
            assertEquals(1, consumed);
        }
        assertNull(redis.opsForValue().get(RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + prefix));
    }
}
