package com.yonagi.verse.service.impl;

import com.yonagi.verse.support.MySqlTestDatabase;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.database.VerseMetaObjectHandler;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.security.UserSecurityLocks;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.mapper.UserSecurityGuardMapper;
import com.yonagi.verse.dto.req.UserResetPasswordReqDTO;
import com.yonagi.verse.dto.req.UserVerifyPhoneCodeReqDTO;
import com.yonagi.verse.service.LoginDeviceService;
import com.yonagi.verse.service.PasswordResetCredentialStore;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yonagi.verse.service.messaging.VerificationCodeStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Redis 凭证和用户锁配合 MySQL 事务；仅清理本测试的随机凭证和用户键。 */
@EnabledIfSystemProperty(named = "messaging.redis-it", matches = "true")
class UserPasswordResetRedisIntegrationTest {
    private static RedissonClient client;
    private static RedissonConnectionFactory factory;
    private static StringRedisTemplate redis;
    private MySqlTestDatabase database;
    private JdbcTemplate jdbc;
    private UserServiceImpl service;
    private VerificationCodeStore codes;
    private LoginDeviceService devices;
    private UserSecurityLocks locks;
    private PasswordResetCredentialStore credentials;
    private UserMapper users;
    private long userId;
    private String phoneHash;
    private String codeKey;
    private String tokenKey;

    @BeforeAll static void connect() throws Exception {
        var config = new Config(); config.setThreads(2); config.setNettyThreads(2);
        config.useSingleServer().setAddress(System.getProperty("messaging.redis-address", "redis://127.0.0.1:6379"))
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(16);
        client = Redisson.create(config);
        factory = new RedissonConnectionFactory(client); factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll static void disconnect() throws Exception {
        if (client != null) client.shutdown();
        if (factory != null) factory.destroy();
    }

    @BeforeEach void setup() throws Exception {
        userId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        phoneHash = "reset-it-" + UUID.randomUUID();
        codeKey = RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + phoneHash;
        tokenKey = RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + phoneHash;
        database = MySqlTestDatabase.create();
        jdbc = new JdbcTemplate(database);
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));
        for (String table : List.of("t_user", "t_user_security_guard")) {
            var ddl = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS `?" + table + "`?\\s*\\(.*?;").matcher(schema);
            assertTrue(ddl.find(), table); jdbc.execute(ddl.group());
        }
        jdbc.update("INSERT INTO t_user(user_id,username,nickname,password,email,email_hash,phone_hash) VALUES(?,?,'测试用户','old-password','encrypted','email',?)",
                userId, "user-" + userId, phoneHash);
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig global = GlobalConfigUtils.defaults().setMetaObjectHandler(new VerseMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addMapper(UserMapper.class); configuration.addMapper(UserSecurityGuardMapper.class);
        var sessions = new MybatisSqlSessionFactoryBean(); sessions.setDataSource(database);
        sessions.setConfiguration(configuration); sessions.setGlobalConfig(global);
        var session = new SqlSessionTemplate(Objects.requireNonNull(sessions.getObject()));
        var aes = mock(AesUtil.class); when(aes.hashForLookup("13800138000")).thenReturn(phoneHash);
        var encoder = mock(PasswordEncoder.class); when(encoder.encode(anyString())).thenAnswer(i -> "encoded:" + i.getArgument(0));
        devices = mock(LoginDeviceService.class); locks = spy(new UserSecurityLocks(client));
        codes = new VerificationCodeStore(redis);
        credentials = spy(new PasswordResetCredentialStore(redis));
        users = spy(session.getMapper(UserMapper.class));
        Map<Class<?>, Object> dependencies = new HashMap<>();
        dependencies.put(AesUtil.class, aes); dependencies.put(PasswordEncoder.class, encoder);
        dependencies.put(StringRedisTemplate.class, redis); dependencies.put(UserSecurityLocks.class, locks);
        dependencies.put(UserSecurityGuardMapper.class, session.getMapper(UserSecurityGuardMapper.class));
        dependencies.put(LoginDeviceService.class, devices); dependencies.put(VerificationCodeStore.class, codes);
        dependencies.put(PasswordResetCredentialStore.class, credentials);
        dependencies.put(JwtUtil.class, new JwtUtil("reset-test-secret-key-with-at-least-32-bytes", 600_000));
        var constructor = UserServiceImpl.class.getConstructors()[0];
        Object[] arguments = new Object[constructor.getParameterCount()];
        for (int n = 0; n < arguments.length; n++) {
            Class<?> type = constructor.getParameterTypes()[n];
            arguments[n] = dependencies.computeIfAbsent(type, key -> mock(key));
        }
        var target = (UserServiceImpl) constructor.newInstance(arguments);
        ReflectionTestUtils.setField(target, "baseMapper", users);
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database), new AnnotationTransactionAttributeSource()));
        service = (UserServiceImpl) proxy.getProxy(); ReflectionTestUtils.setField(target, "self", service);
    }

    @AfterEach void clear() {
        redis.delete(List.of(codeKey, codeKey + ":owner", codeKey + ":backup:delivery", codeKey + ":rate", tokenKey,
                RedisKeyConstant.VERIFICATION_SMS_DAILY_KEY + phoneHash, RedisKeyConstant.USER_DEVICES_KEY + userId));
        if (database != null) database.close();
    }

    @Test void concurrentCodeVerificationIssuesExactlyOneToken() throws Exception {
        redis.opsForValue().set(codeKey, "123456", 5, TimeUnit.MINUTES);
        assertEquals(1, concurrent(() -> service.verifyCode(verification("123456")) != null));
        assertNull(redis.opsForValue().get(codeKey)); assertNotNull(redis.opsForValue().get(tokenKey));
    }

    @Test void concurrentResetWithDifferentPasswordsUpdatesExactlyOnce() throws Exception {
        issueToken("reset-token");
        assertEquals(1, concurrent(() -> service.resetPassword(reset("reset-token", UUID.randomUUID().toString()))));
        assertTrue(password().startsWith("encoded:")); assertNull(redis.opsForValue().get(tokenKey));
        verify(devices, times(1)).logoutAllDevice(userId);
        assertEquals(1L, jdbc.queryForObject("SELECT security_version FROM t_user_security_guard WHERE user_id=?", Long.class, userId));
    }

    @Test void wrongAndExpiredCredentialsDoNotModifyPasswordOrValidCredentials() {
        redis.opsForValue().set(codeKey, "123456", 5, TimeUnit.MINUTES);
        assertThrows(ClientException.class, () -> service.verifyCode(verification("654321")));
        assertEquals("123456", redis.opsForValue().get(codeKey));
        issueToken("reset-token");
        assertThrows(ClientException.class, () -> service.resetPassword(reset("wrong-token", "new-password")));
        assertEquals(DigestUtil.md5Hex("reset-token"), redis.opsForValue().get(tokenKey));
        redis.delete(List.of(codeKey, tokenKey));
        assertThrows(ClientException.class, () -> service.verifyCode(verification("123456")));
        assertThrows(ClientException.class, () -> service.resetPassword(reset("reset-token", "new-password")));
        assertEquals("old-password", password()); verifyNoInteractions(devices);
    }

    @Test void committedResetCannotDeleteNewlyIssuedToken() {
        issueToken("reset-token");
        doAnswer(invocation -> { issueToken("next-token"); return null; }).when(devices).logoutAllDevice(userId);
        assertTrue(service.resetPassword(reset("reset-token", "new-password")));
        assertEquals(DigestUtil.md5Hex("next-token"), redis.opsForValue().get(tokenKey));
        assertThrows(ClientException.class, () -> service.resetPassword(reset("reset-token", "another-password")));
    }

    @Test void transactionFailureRollsBackPasswordWithoutRestoringConsumedToken() {
        issueToken("reset-token");
        doThrow(new IllegalStateException("device persistence unavailable")).when(devices).logoutAllDevice(userId);
        assertThrows(IllegalStateException.class, () -> service.resetPassword(reset("reset-token", "new-password")));
        assertEquals("old-password", password()); assertNull(redis.opsForValue().get(tokenKey));
        assertThrows(ClientException.class, () -> service.resetPassword(reset("reset-token", "retry-password")));
        verify(devices, times(1)).logoutAllDevice(userId);
    }

    @Test void accountDisabledOrPhoneChangedBeforeLockPreservesToken() {
        issueToken("reset-token"); jdbc.update("UPDATE t_user SET status=0 WHERE user_id=?", userId);
        assertThrows(ClientException.class, () -> service.resetPassword(reset("reset-token", "new-password")));
        assertEquals(DigestUtil.md5Hex("reset-token"), redis.opsForValue().get(tokenKey));
        jdbc.update("UPDATE t_user SET status=1 WHERE user_id=?", userId);
        doAnswer(invocation -> {
            jdbc.update("UPDATE t_user SET phone_hash='changed-phone' WHERE user_id=?", userId);
            return invocation.callRealMethod();
        }).when(locks).withUser(eq(userId), any());
        assertThrows(ClientException.class, () -> service.resetPassword(reset("reset-token", "new-password")));
        assertEquals(DigestUtil.md5Hex("reset-token"), redis.opsForValue().get(tokenKey));
        assertEquals("old-password", password()); verifyNoInteractions(devices);
    }

    @Test void consumedVerificationCannotBeRestoredByDeliveryFailure() {
        var reservation = codes.reserve(codeKey, codeKey + ":rate", phoneHash, "123456", "delivery", 10);
        service.verifyCode(verification("123456")); codes.restore(reservation);
        assertNull(redis.opsForValue().get(codeKey)); assertFalse(codes.isCurrent(reservation));
        assertNotNull(redis.opsForValue().get(tokenKey));
    }

    @Test void invalidTokenStorageTypeDoesNotConsumeVerification() {
        redis.opsForValue().set(codeKey, "123456", 5, TimeUnit.MINUTES);
        redis.opsForList().leftPush(tokenKey, "invalid-type");
        assertThrows(RuntimeException.class, () -> service.verifyCode(verification("123456")));
        assertEquals("123456", redis.opsForValue().get(codeKey));
    }

    @Test void uncertainRedisConsumptionNeverUpdatesPasswordOrRestoresToken() {
        issueToken("reset-token");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new ServerException("模拟消费成功后的响应丢失");
        }).when(credentials).consume(phoneHash, DigestUtil.md5Hex("reset-token"));
        assertThrows(ServerException.class, () -> service.resetPassword(reset("reset-token", "new-password")));
        assertEquals("old-password", password()); assertNull(redis.opsForValue().get(tokenKey));
        verifyNoInteractions(devices);
    }

    @Test void zeroUpdatedRowsIsFailureAndDoesNotRestoreConsumedToken() {
        issueToken("reset-token");
        doReturn(0).when(users).update(any(Wrapper.class));
        assertThrows(ServerException.class, () -> service.resetPassword(reset("reset-token", "new-password")));
        assertEquals("old-password", password()); assertNull(redis.opsForValue().get(tokenKey));
        verifyNoInteractions(devices);
    }

    private int concurrent(Supplier<Boolean> action) throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<Boolean>>();
            for (int n = 0; n < 8; n++) futures.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { return action.get(); } catch (ClientException rejected) { return false; }
            }));
            start.countDown(); int successes = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) successes++;
            return successes;
        }
    }
    private void issueToken(String token) { redis.opsForValue().set(tokenKey, DigestUtil.md5Hex(token), 10, TimeUnit.MINUTES); }
    private String password() { return jdbc.queryForObject("SELECT password FROM t_user WHERE user_id=?", String.class, userId); }
    private UserVerifyPhoneCodeReqDTO verification(String code) {
        var request = new UserVerifyPhoneCodeReqDTO(); request.setPhone("13800138000"); request.setCode(code); return request;
    }
    private UserResetPasswordReqDTO reset(String token, String password) {
        var request = new UserResetPasswordReqDTO(); request.setPhone("13800138000"); request.setToken(token); request.setPassword(password); return request;
    }
}
