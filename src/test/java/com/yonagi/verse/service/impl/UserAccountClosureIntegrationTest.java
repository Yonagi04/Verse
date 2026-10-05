package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.UserClosedEvent;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.handler.TokenUsageEventHandler;
import com.yonagi.verse.async.handler.UserClosedEventHandler;
import com.yonagi.verse.async.outbox.*;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.database.VerseMetaObjectHandler;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.ConfirmCloseAccountReqDTO;
import com.yonagi.verse.dto.req.TenantCreateReqDTO;
import com.yonagi.verse.service.*;
import com.yonagi.verse.service.pricing.CostResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 默认使用 H2；CI/显式测试服务使用隔离 MySQL 临时库，执行同一组事务和清理契约。 */
public class UserAccountClosureIntegrationTest {
    private DataSource database;
    private DriverManagerDataSource server;
    private String databaseName;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager manager;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private UserMapper users;
    private ApiKeyMapper keys;
    private LlmServiceMapper models;
    private DomainEventOutboxMapper outbox;
    private ReliableDomainEventPublisher events;
    private UserService account;
    private UserClosedEventHandler handler;
    private UserAccountCleanupService cleanup;
    private final LocalDateTime bucket = LocalDateTime.of(2026, 10, 1, 10, 0);

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("VERSE_BUDGET_TEST_URL");
        if (url == null || url.isBlank()) {
            database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        } else {
            server = new DriverManagerDataSource(url, System.getenv("VERSE_BUDGET_TEST_USER"), System.getenv("VERSE_BUDGET_TEST_PASSWORD"));
            databaseName = "verse_closure_test_" + UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(server).execute("CREATE DATABASE " + databaseName);
            String databaseUrl = url.contains("?") ? url.replace("?", "/" + databaseName + "?") : url + "/" + databaseName;
            database = new DriverManagerDataSource(databaseUrl, System.getenv("VERSE_BUDGET_TEST_USER"), System.getenv("VERSE_BUDGET_TEST_PASSWORD"));
        }
        jdbc = new JdbcTemplate(database);
        if (server == null) {
            jdbc.execute("SET MODE MySQL");
            jdbc.execute("CREATE ALIAS DATE_FORMAT FOR 'com.yonagi.verse.service.impl.UserAccountClosureIntegrationTest.mysqlDateFormat'");
        }
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));
        for (String table : List.of("t_user", "t_tenant", "t_user_tenant", "t_api_key", "t_llm_service",
                "t_token_usage", "t_token_usage_cost", "t_token_usage_hourly_agg", "t_token_usage_outbox",
                "t_domain_event_outbox", "t_usage_projection_guard")) {
            var matcher = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS `?" + Pattern.quote(table) + "`?\\s*\\(.*?;").matcher(schema);
            assertTrue(matcher.find(), table);
            String ddl = matcher.group();
            // H2 的 JSON 字符串绑定语义不同；MySQL 使用原始 schema。
            if (server == null) {
                ddl = ddl.replace(" JSON ", " VARCHAR(20000) ")
                        .replaceAll("(?i)(KEY\\s+`?)([a-zA-Z_][a-zA-Z0-9_]*)(`?)", "$1" + table + "_$2$3");
            }
            try (var connection = database.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));
            }
        }
        jdbc.update("INSERT INTO t_usage_projection_guard VALUES(1)");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig globalConfig = GlobalConfigUtils.defaults().setMetaObjectHandler(new VerseMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        for (Class<?> type : List.of(UserMapper.class, ApiKeyMapper.class, LlmServiceMapper.class,
                TenantMapper.class, UserTenantMapper.class, TokenUsageMapper.class, TokenUsageCostMapper.class,
                TokenUsageOutboxMapper.class, TokenUsageHourlyAggMapper.class, UsageCleanupMapper.class, DomainEventOutboxMapper.class)) {
            configuration.addMapper(type);
        }
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(database); factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        manager = new DataSourceTransactionManager(database);
        users = session.getMapper(UserMapper.class); keys = session.getMapper(ApiKeyMapper.class);
        models = session.getMapper(LlmServiceMapper.class); outbox = spy(session.getMapper(DomainEventOutboxMapper.class));
        events = transactional(new ReliableDomainEventPublisherImpl(outbox, mock(DomainOutboxMetrics.class)));
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForValue().get(anyString())).thenReturn("123456");
        when(redis.opsForValue().setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        dependencies.put(StringRedisTemplate.class, redis);
        dependencies.put(UserMapper.class, users);
        dependencies.put(TenantMapper.class, session.getMapper(TenantMapper.class));
        dependencies.put(ReliableDomainEventPublisher.class, events);
        var memberships = transactional(bean(UserTenantServiceImpl.class, session.getMapper(UserTenantMapper.class)));
        dependencies.put(UserTenantService.class, memberships);
        var tenants = transactional(bean(TenantCrudServiceImpl.class, null));
        dependencies.put(TenantCrudService.class, tenants);
        account = transactional(bean(UserServiceImpl.class, users));
        // 安全域的外围行为继续隔离，注销持有的用户行锁使用真实数据库实现。
        when(((UserSecurityGuardMapper) dependencies.get(UserSecurityGuardMapper.class)).lockUser(anyLong()))
                .thenAnswer(invocation -> users.lockResourceOwner(invocation.getArgument(0)));
        var api = transactional(bean(ApiKeyServiceImpl.class, keys));
        var llms = transactional(bean(LlmManageServiceImpl.class, models));
        cleanup = transactional(new UserAccountCleanupService(users, session.getMapper(UsageCleanupMapper.class),
                session.getMapper(TokenUsageHourlyAggMapper.class), events, outbox));
        handler = new UserClosedEventHandler(users, api, memberships, tenants, llms, cleanup);
        seedResources();
    }

    private <T> T bean(Class<T> type, Object baseMapper) throws Exception {
        var constructor = type.getConstructors()[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(parameter -> dependencies.computeIfAbsent(parameter, key -> mock(key))).toArray();
        T result = type.cast(constructor.newInstance(arguments));
        if (baseMapper != null) ReflectionTestUtils.setField(result, "baseMapper", baseMapper);
        return result;
    }

    /** H2 fixture 补充 MySQL 时间格式函数；真实 MySQL 分支使用数据库原生实现。 */
    public static String mysqlDateFormat(java.sql.Timestamp time, String format) {
        String pattern = format.replace("%Y", "yyyy").replace("%m", "MM").replace("%d", "dd")
                .replace("%H", "HH").replace("%i", "mm").replace("%s", "ss");
        return time.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern(pattern));
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        if (database instanceof EmbeddedDatabase embedded) embedded.shutdown();
        if (server != null && databaseName != null && databaseName.matches("verse_closure_test_[0-9a-f]{32}")) {
            new JdbcTemplate(server).execute("DROP DATABASE " + databaseName);
        }
    }

    private void seedResources() {
        for (int user : List.of(1, 2)) {
            jdbc.update("INSERT INTO t_user(user_id,username,nickname,password,email,email_hash,phone_hash,last_active_tenant_id) "
                    + "VALUES (?, ?, '测试用户','hash','encrypted',?, ?,20)", user, "user" + user, "email" + user, "phone" + user);
        }
        jdbc.update("INSERT INTO t_tenant(tenant_id,name,type,owner_id) VALUES (10,'本人个人','PERSONAL',1),(11,'他人个人','PERSONAL',2),(20,'团队','TEAM',1)");
        jdbc.update("INSERT INTO t_user_tenant(user_id,tenant_id,role,joined_at,favorite,pinned) VALUES (1,10,'SUPER_ADMIN',CURRENT_TIMESTAMP,1,1),(1,20,'ADMIN',CURRENT_TIMESTAMP,1,1),(2,20,'ADMIN',CURRENT_TIMESTAMP,1,1)");
        jdbc.update("INSERT INTO t_api_key(api_key_id,user_id,tenant_id,api_key,key_prefix) VALUES (101,1,10,'key101','sk_one'),(102,1,20,'key102','sk_two'),(103,2,20,'key103','sk_other')");
        jdbc.update("INSERT INTO t_llm_service(service_id,tenant_id,name,provider,api_url,api_key,model_name,created_by) VALUES (201,10,'personal','openai','url','secret','model',1),(202,20,'shared','openai','url','secret','model',1),(203,20,'other','openai','url','secret','model',2)");
        jdbc.update("INSERT INTO t_llm_service(service_id,tenant_id,name,provider,api_key,created_by,del_flag,status) VALUES(204,10,'personal','openai','old-secret',1,1,0)");
        insertUsage(1, 1); insertUsage(2, 1);
    }

    private void insertUsage(long userId, int count) {
        for (int n = 0; n < count; n++) {
            String event = "usage-" + userId + "-" + UUID.randomUUID();
            jdbc.update("INSERT INTO t_token_usage(user_id,tenant_id,api_key_id,service_id,model,event_id,request_started_at) VALUES (?,20,103,203,'model',?,?)", userId, event, bucket);
            Long id = jdbc.queryForObject("SELECT id FROM t_token_usage WHERE event_id=?", Long.class, event);
            jdbc.update("INSERT INTO t_token_usage_cost(usage_id,cost_status) VALUES (?,'UNPRICED')", id);
            jdbc.update("INSERT INTO t_token_usage_outbox(event_id,user_id,tenant_id,event_type,message_key,payload_json,next_retry_at) VALUES (?, ?,20,'TOKEN_USAGE','key','{}',CURRENT_TIMESTAMP)", event, userId);
        }
        jdbc.update("INSERT IGNORE INTO t_token_usage_hourly_agg(tenant_id,user_id,api_key_id,service_id,model,bucket_start) VALUES (20,?,103,203,'model',?)", userId, bucket);
    }

    private void close() {
        ConfirmCloseAccountReqDTO request = new ConfirmCloseAccountReqDTO(); request.setCode("123456");
        assertTrue(account.confirmCloseAccount(1L, request));
    }

    private long scalar(String sql) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class)); }

    private void makeUserTeamOwner() {
        jdbc.update("UPDATE t_tenant SET owner_id=1,name='研发团队' WHERE tenant_id=20");
        jdbc.update("UPDATE t_user_tenant SET role=CASE WHEN user_id=1 THEN 'SUPER_ADMIN' ELSE 'ADMIN' END WHERE tenant_id=20");
    }

    @Test
    void allClosureEntrypointsListOnlyTeamsRequiringHandoverWithoutSideEffects() {
        makeUserTeamOwner();
        jdbc.update("INSERT INTO t_tenant(tenant_id,name,type,owner_id,status,del_flag) VALUES (30,'已停用团队','TEAM',1,0,0),(31,'数据团队','TEAM',1,1,0),(32,'单人团队','TEAM',1,1,0),(40,'已删除团队','TEAM',1,1,1),(50,'已退出团队','TEAM',1,1,0),(60,'他人团队','TEAM',2,1,0)");
        jdbc.update("INSERT INTO t_user_tenant(user_id,tenant_id,role,left_at) VALUES (1,30,'SUPER_ADMIN',NULL),(2,30,'MEMBER',NULL),(1,31,'SUPER_ADMIN',NULL),(2,31,'MEMBER',NULL),(1,32,'SUPER_ADMIN',NULL),(1,40,'SUPER_ADMIN',NULL),(1,50,'SUPER_ADMIN',CURRENT_TIMESTAMP),(2,60,'SUPER_ADMIN',NULL)");
        StringRedisTemplate redis = (StringRedisTemplate) dependencies.get(StringRedisTemplate.class);
        clearInvocations(redis);
        for (Runnable attempt : List.<Runnable>of(() -> account.prepareCloseAccount(1L),
                () -> account.closeAccountSendCode(1L), this::close)) {
            ClientException failure = assertThrows(ClientException.class, attempt::run);
            assertEquals("B000224", failure.getErrorCode());
            assertEquals("您仍担任以下团体租户的超级管理员，请先完成租户交接后再注销：研发团队、数据团队", failure.getErrorMessage());
        }
        verifyNoInteractions(redis);
        assertEquals(1, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(0, scalar("SELECT del_flag FROM t_user WHERE user_id=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM t_api_key WHERE user_id=1 AND status=1"));
    }

    @Test
    void disabledTeamDoesNotBlockAnyClosureEntrypoint() {
        makeUserTeamOwner();
        jdbc.update("UPDATE t_tenant SET status=0 WHERE tenant_id=20");

        assertNotNull(account.prepareCloseAccount(1L));
        assertTrue(account.closeAccountSendCode(1L));
        assertEquals(1, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));

        close();
        assertEquals(2, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(1, scalar("SELECT del_flag FROM t_user WHERE user_id=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_domain_event_outbox WHERE event_type='USER_CLOSED'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_tenant WHERE tenant_id=20 AND status=0 AND del_flag=0 AND owner_id=1"));
    }

    @ParameterizedTest
    @CsvSource({"false,1", "true,1", "false,0", "true,0"})
    void soleRemainingTeamMemberDoesNotBlockAnyClosureEntrypoint(boolean hasDepartedMember, int tenantStatus) {
        makeUserTeamOwner();
        jdbc.update("UPDATE t_tenant SET status=? WHERE tenant_id=20", tenantStatus);
        if (hasDepartedMember) {
            jdbc.update("UPDATE t_user_tenant SET left_at=CURRENT_TIMESTAMP WHERE user_id=2 AND tenant_id=20");
        } else {
            jdbc.update("DELETE FROM t_user_tenant WHERE user_id=2 AND tenant_id=20");
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_user_tenant WHERE tenant_id=20 AND left_at IS NULL"));

        assertNotNull(account.prepareCloseAccount(1L));
        assertTrue(account.closeAccountSendCode(1L));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
        close();
        assertEquals(2, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(1, scalar("SELECT del_flag FROM t_user WHERE user_id=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_domain_event_outbox WHERE event_type='USER_CLOSED'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_tenant WHERE tenant_id=20 AND status=0 AND del_flag=1"));
        handler.onEvent(UserClosedEvent.initial(1L));
        assertNotNull(users.selectCleanupState(1L).getResourceCleanupAt());
    }

    @Test
    void confirmationRechecksOtherMembersAfterSingleMemberPrepareSucceeded() {
        makeUserTeamOwner();
        jdbc.update("DELETE FROM t_user_tenant WHERE user_id=2 AND tenant_id=20");
        assertNotNull(account.prepareCloseAccount(1L));
        assertTrue(account.closeAccountSendCode(1L));
        jdbc.update("INSERT INTO t_user_tenant(user_id,tenant_id,role) VALUES(2,20,'MEMBER')");

        ClientException failure = assertThrows(ClientException.class, this::close);
        assertEquals("B000224", failure.getErrorCode());
        assertEquals("您仍担任以下团体租户的超级管理员，请先完成租户交接后再注销：研发团队", failure.getErrorMessage());
        assertEquals(1, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
        verify((StringRedisTemplate) dependencies.get(StringRedisTemplate.class), never()).delete(
                com.yonagi.verse.common.constant.RedisKeyConstant.USER_CLOSE_ACCOUNT_SENDING_CODE_KEY + 1);
    }

    @Test
    void confirmationRechecksRolesEvenWhenPrepareAndCodeSendingAlreadySucceeded() {
        assertNotNull(account.prepareCloseAccount(1L));
        assertTrue(account.closeAccountSendCode(1L));
        makeUserTeamOwner();
        ClientException failure = assertThrows(ClientException.class, this::close);
        assertEquals("B000224", failure.getErrorCode());
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
        verify((StringRedisTemplate) dependencies.get(StringRedisTemplate.class), never()).delete(
                com.yonagi.verse.common.constant.RedisKeyConstant.USER_CLOSE_ACCOUNT_SENDING_CODE_KEY + 1);
    }

    @Test
    void transferringSuperAdminAllowsOldOwnerToCloseAndPreservesNewOwner() {
        makeUserTeamOwner();
        assertThrows(ClientException.class, this::close);
        var transfer = transactional(new TenantAdminTransferService(session.getMapper(TenantMapper.class),
                session.getMapper(UserTenantMapper.class), users, mock(TenantActivityRecorder.class)));
        assertTrue(transfer.transfer(1L,20L,2L));
        assertNotNull(account.prepareCloseAccount(1L));
        close(); handler.onEvent(UserClosedEvent.initial(1L));
        assertEquals(2, scalar("SELECT owner_id FROM t_tenant WHERE tenant_id=20"));
        assertEquals(1, scalar("SELECT status FROM t_tenant WHERE tenant_id=20"));
        assertEquals("SUPER_ADMIN", jdbc.queryForObject("SELECT role FROM t_user_tenant WHERE user_id=2 AND tenant_id=20", String.class));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_user_tenant WHERE user_id=2 AND tenant_id=20 AND left_at IS NULL"));
        assertNotNull(keys.selectAuthState(103L,"key103"));
    }

    @Test
    void leftAndDeletedTeamsDoNotBlockPersonalSuperAdminFromClosing() {
        makeUserTeamOwner();
        jdbc.update("UPDATE t_user_tenant SET left_at=CURRENT_TIMESTAMP WHERE user_id=1 AND tenant_id=20");
        jdbc.update("INSERT INTO t_tenant(tenant_id,name,type,owner_id,del_flag) VALUES(30,'历史团队','TEAM',1,1)");
        jdbc.update("INSERT INTO t_user_tenant(user_id,tenant_id,role) VALUES(1,30,'SUPER_ADMIN')");
        assertNotNull(account.prepareCloseAccount(1L));
        close();
        assertEquals(2, scalar("SELECT status FROM t_user WHERE user_id=1"));
    }

    private void makeOtherUserTeamOwner() {
        jdbc.update("UPDATE t_tenant SET owner_id=2 WHERE tenant_id=20");
        jdbc.update("UPDATE t_user_tenant SET role=CASE WHEN user_id=2 THEN 'SUPER_ADMIN' ELSE 'ADMIN' END WHERE tenant_id=20");
    }

    private static void awaitSignal(CountDownLatch signal) {
        try { assertTrue(signal.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    @Test
    void closingFirstRejectsConcurrentHandoverToTheClosingUser() throws Exception {
        makeOtherUserTeamOwner();
        CountDownLatch start = new CountDownLatch(1), targetLockRequested = new CountDownLatch(1);
        UserMapper transferUsers = spy(users);
        doAnswer(invocation -> {
            targetLockRequested.countDown();
            return users.selectActiveUserForUpdate(1L);
        }).when(transferUsers).selectActiveUserForUpdate(1L);
        var transfer = transactional(new TenantAdminTransferService(session.getMapper(TenantMapper.class),
                session.getMapper(UserTenantMapper.class), transferUsers, mock(TenantActivityRecorder.class)));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var handover = executor.submit(() -> {
                awaitSignal(start);
                return assertThrows(ClientException.class, () -> transfer.transfer(2L,20L,1L));
            });
            TransactionTemplate closing = new TransactionTemplate(manager);
            closing.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            closing.executeWithoutResult(ignored -> {
                users.lockResourceOwner(1L);
                start.countDown();
                awaitSignal(targetLockRequested);
                close();
            });
            assertEquals("B000340", handover.get(5, TimeUnit.SECONDS).getErrorCode());
        }
        assertEquals(2, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(2, scalar("SELECT owner_id FROM t_tenant WHERE tenant_id=20"));
        assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM t_user_tenant WHERE user_id=1 AND tenant_id=20", String.class));
    }

    @Test
    void handoverFirstMakesWaitingClosureRecheckAndRejectNewSuperAdmin() throws Exception {
        makeOtherUserTeamOwner();
        CountDownLatch targetLocked = new CountDownLatch(1), closingRequested = new CountDownLatch(1), release = new CountDownLatch(1);
        UserMapper transferUsers = spy(users);
        doAnswer(invocation -> {
            var target = users.selectActiveUserForUpdate(1L);
            targetLocked.countDown();
            awaitSignal(release);
            return target;
        }).when(transferUsers).selectActiveUserForUpdate(1L);
        var transfer = transactional(new TenantAdminTransferService(session.getMapper(TenantMapper.class),
                session.getMapper(UserTenantMapper.class), transferUsers, mock(TenantActivityRecorder.class)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var handover = executor.submit(() -> transfer.transfer(2L,20L,1L));
            awaitSignal(targetLocked);
            var closing = executor.submit(() -> {
                closingRequested.countDown();
                return assertThrows(ClientException.class, this::close);
            });
            awaitSignal(closingRequested);
            assertThrows(TimeoutException.class, () -> closing.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(handover.get(5, TimeUnit.SECONDS));
            assertEquals("B000224", closing.get(5, TimeUnit.SECONDS).getErrorCode());
        } finally { release.countDown(); }
        assertEquals(1, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(1, scalar("SELECT owner_id FROM t_tenant WHERE tenant_id=20"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
    }

    @Test
    void closingFirstRejectsJoiningBeforeAsyncCleanup() throws Exception {
        makeUserTeamOwner();
        jdbc.update("DELETE FROM t_user_tenant WHERE user_id=2 AND tenant_id=20");
        CountDownLatch closureStaged = new CountDownLatch(1), joinRequested = new CountDownLatch(1);
        UserTenantMapper joiningMapper = spy(session.getMapper(UserTenantMapper.class));
        doAnswer(invocation -> {
            joinRequested.countDown();
            return session.getMapper(UserTenantMapper.class).lockActiveJoiningTenant(20L);
        }).when(joiningMapper).lockActiveJoiningTenant(20L);
        UserTenantService joining = transactional(bean(UserTenantServiceImpl.class, joiningMapper));
        doAnswer(invocation -> {
            closureStaged.countDown();
            awaitSignal(joinRequested);
            return session.getMapper(DomainEventOutboxMapper.class).insert(invocation.getArgument(0));
        }).when(outbox).insert(any(DomainEventOutboxDO.class));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var closure = executor.submit(this::close);
            awaitSignal(closureStaged);
            var join = executor.submit(() -> assertThrows(ClientException.class,
                    () -> joining.createUserTenant(2L, 20L, "MEMBER")));
            closure.get(5, TimeUnit.SECONDS);
            assertEquals(TenantErrorCodeEnum.TENANT_NOT_EXIST.code(), join.get(5, TimeUnit.SECONDS).getErrorCode());
        }
        assertEquals(2, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_tenant WHERE tenant_id=20 AND status=0 AND del_flag=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_user_tenant WHERE user_id=2 AND tenant_id=20"));
        assertNull(users.selectCleanupState(1L).getResourceCleanupAt());
    }

    @Test
    void joiningFirstMakesClosureRejectWithoutDeletingTeam() throws Exception {
        makeUserTeamOwner();
        jdbc.update("DELETE FROM t_user_tenant WHERE user_id=2 AND tenant_id=20");
        CountDownLatch closingRequested = new CountDownLatch(1);
        UserTenantService memberships = (UserTenantService) dependencies.get(UserTenantService.class);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var closure = new TransactionTemplate(manager).execute(ignored -> {
                assertTrue(memberships.createUserTenant(2L, 20L, "MEMBER"));
                var pending = executor.submit(() -> {
                    closingRequested.countDown();
                    return assertThrows(ClientException.class, this::close);
                });
                awaitSignal(closingRequested);
                assertThrows(TimeoutException.class, () -> pending.get(200, TimeUnit.MILLISECONDS));
                return pending;
            });
            assertEquals("B000224", Objects.requireNonNull(closure).get(5, TimeUnit.SECONDS).getErrorCode());
        }
        assertEquals(1, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_tenant WHERE tenant_id=20 AND status=1 AND del_flag=0"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM t_user_tenant WHERE tenant_id=20 AND left_at IS NULL"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
    }

    @Test
    void historicalCleanupDeletesOwnedTeamsWithoutOtherMembersAndPreservesSharedTeams() {
        makeUserTeamOwner();
        jdbc.update("UPDATE t_tenant SET status=0 WHERE tenant_id=20");
        jdbc.update("UPDATE t_user_tenant SET left_at=CURRENT_TIMESTAMP WHERE user_id=2 AND tenant_id=20");
        jdbc.update("INSERT INTO t_tenant(tenant_id,name,type,owner_id) VALUES(30,'多人团队','TEAM',1),(40,'他人单人团队','TEAM',2)");
        jdbc.update("INSERT INTO t_user_tenant(user_id,tenant_id,role) VALUES(1,30,'SUPER_ADMIN'),(2,30,'MEMBER'),(2,40,'SUPER_ADMIN')");
        jdbc.update("UPDATE t_user SET status=2,del_flag=1 WHERE user_id=1");
        handler.onEvent(UserClosedEvent.initial(1L));
        handler.onEvent(UserClosedEvent.initial(1L));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_tenant WHERE tenant_id=10 AND status=0 AND del_flag=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_tenant WHERE tenant_id=20 AND status=0 AND del_flag=1"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM t_tenant WHERE tenant_id IN (30,40) AND status=1 AND del_flag=0"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_user_tenant WHERE user_id=2 AND tenant_id=30 AND left_at IS NULL"));
        assertNotNull(users.selectCleanupState(1L).getResourceCleanupAt());
    }

    @Test
    void teamCreatedBeforeUserLockRequiresRetryAndPreservesVerificationCode() throws Exception {
        makeOtherUserTeamOwner();
        CountDownLatch userLockRequested = new CountDownLatch(1), created = new CountDownLatch(1);
        doAnswer(invocation -> {
            userLockRequested.countDown();
            awaitSignal(created);
            return users.lockResourceOwner(1L);
        }).when((UserSecurityGuardMapper) dependencies.get(UserSecurityGuardMapper.class)).lockUser(1L);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var closure = executor.submit(() -> assertThrows(ClientException.class, this::close));
            awaitSignal(userLockRequested);
            try {
                TenantCreateReqDTO request = new TenantCreateReqDTO(); request.setName("并发创建团队");
                assertTrue(((TenantCrudService) dependencies.get(TenantCrudService.class)).createTenant(1L, request));
            } finally {
                created.countDown();
            }
            assertEquals("B000225", closure.get(5, TimeUnit.SECONDS).getErrorCode());
        }
        assertEquals(1, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_tenant WHERE owner_id=1 AND type='TEAM' AND status=1 AND del_flag=0"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
        verify((StringRedisTemplate) dependencies.get(StringRedisTemplate.class), never()).delete(
                com.yonagi.verse.common.constant.RedisKeyConstant.USER_CLOSE_ACCOUNT_SENDING_CODE_KEY + 1);
    }

    @Test
    void membershipCanRejoinWhileActiveButCannotBeRestoredAfterClosure() {
        UserTenantService memberships = (UserTenantService) dependencies.get(UserTenantService.class);
        jdbc.update("UPDATE t_user_tenant SET left_at=CURRENT_TIMESTAMP WHERE user_id=1 AND tenant_id=20");
        assertTrue(memberships.createUserTenant(1L, 20L, "MEMBER"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_user_tenant WHERE user_id=1 AND tenant_id=20 AND left_at IS NULL"));
        close();
        handler.onEvent(UserClosedEvent.initial(1L));
        assertThrows(ClientException.class, () -> memberships.createUserTenant(1L, 20L, "MEMBER"));
        assertThrows(ClientException.class, () -> memberships.createUserTenant(2L, 10L, "MEMBER"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_user_tenant WHERE user_id=1 AND left_at IS NULL"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_user_tenant WHERE user_id=2 AND tenant_id=10"));
    }

    @Test
    void closureAtomicallySchedulesCleanupAndInvalidatesKeysBeforeConsumerRuns() {
        assertNotNull(keys.selectAuthState(102L, "key102"));
        assertEquals(1, models.countCallableService(20L, 202L));
        close();
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_domain_event_outbox WHERE event_type='USER_CLOSED'"));
        assertEquals(1, scalar("SELECT status FROM t_api_key WHERE api_key_id=102"));
        assertNull(keys.selectAuthState(102L, "key102"));
        assertEquals(0, models.countCallableService(20L, 202L));
        handler.onEvent(UserClosedEvent.initial(1L));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_api_key WHERE user_id=1 AND status<>0"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_user_tenant WHERE user_id=1 AND (left_at IS NULL OR favorite<>0 OR pinned<>0)"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_tenant WHERE tenant_id=10 AND status=0 AND del_flag=1"));
        assertEquals(1, scalar("SELECT status FROM t_tenant WHERE tenant_id=20"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_llm_service WHERE created_by=1 AND (status<>0 OR del_flag<>1 OR api_key<>'')"));
        for (String table : List.of("t_token_usage", "t_token_usage_hourly_agg", "t_token_usage_outbox")) {
            assertEquals(0, scalar("SELECT COUNT(*) FROM " + table + " WHERE user_id=1"));
            assertEquals(1, scalar("SELECT COUNT(*) FROM " + table + " WHERE user_id=2"));
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_token_usage_cost"));
        assertEquals(1, scalar("SELECT status FROM t_api_key WHERE user_id=2"));
        assertEquals(1, models.countCallableService(20L, 203L));
        LocalDateTime completed = users.selectCleanupState(1L).getResourceCleanupAt();
        assertNotNull(completed);
        handler.onEvent(UserClosedEvent.initial(1L));
        assertEquals(completed, users.selectCleanupState(1L).getResourceCleanupAt());
    }

    @Test
    void failedOutboxStagingRollsBackClosure() {
        makeUserTeamOwner();
        jdbc.update("DELETE FROM t_user_tenant WHERE user_id=2 AND tenant_id=20");
        doThrow(new IllegalStateException("outbox unavailable")).when(outbox).insert(any(DomainEventOutboxDO.class));
        assertThrows(IllegalStateException.class, this::close);
        assertEquals(1, scalar("SELECT status FROM t_user WHERE user_id=1"));
        assertEquals(0, scalar("SELECT del_flag FROM t_user WHERE user_id=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM t_tenant WHERE tenant_id IN (10,20) AND status=1 AND del_flag=0"));
        assertNotNull(keys.selectAuthState(102L, "key102"));
    }

    @Test
    void boundedBatchesContinueAtomicallyAndDoNotMarkCompletionEarly() {
        insertUsage(1, 500); close();
        handler.onEvent(UserClosedEvent.initial(1L));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_token_usage WHERE user_id=1"));
        assertNull(users.selectCleanupState(1L).getResourceCleanupAt());
        String payload = jdbc.queryForObject("SELECT payload_json FROM t_domain_event_outbox WHERE event_id<>?", String.class, UserClosedEvent.initialId(1L));
        handler.onEvent(JSON.parseObject(payload, UserClosedEvent.class));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_token_usage WHERE user_id=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_token_usage_cost"));
        assertNotNull(users.selectCleanupState(1L).getResourceCleanupAt());
        assertEquals(2, scalar("SELECT COUNT(*) FROM t_domain_event_outbox WHERE reconciled_at IS NOT NULL"));
    }

    @Test
    void continuationFailureRollsBackBatchAndCanBeRetried() {
        insertUsage(1, 500); close();
        doThrow(new IllegalStateException("next event unavailable")).when(outbox).insert(any(DomainEventOutboxDO.class));
        assertThrows(IllegalStateException.class, () -> handler.onEvent(UserClosedEvent.initial(1L)));
        assertEquals(501, scalar("SELECT COUNT(*) FROM t_token_usage WHERE user_id=1"));
        assertEquals(502, scalar("SELECT COUNT(*) FROM t_token_usage_cost"));
        assertNull(users.selectCleanupState(1L).getResourceCleanupAt());
        doCallRealMethod().when(outbox).insert(any(DomainEventOutboxDO.class));
        handler.onEvent(UserClosedEvent.initial(1L));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_token_usage WHERE user_id=1"));
    }

    @Test
    void historicalClosuresAreScheduledOnceAndActiveUsersAreProtected() {
        assertThrows(IllegalStateException.class, () -> handler.onEvent(UserClosedEvent.initial(1L)));
        assertEquals(1, scalar("SELECT status FROM t_api_key WHERE api_key_id=102"));
        jdbc.update("UPDATE t_user SET status=2,del_flag=1 WHERE user_id=1");
        var job = new UserClosedCleanupJob(users, cleanup);
        job.backfill(); job.backfill();
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
        handler.onEvent(UserClosedEvent.initial(1L));
        jdbc.update("DELETE FROM t_domain_event_outbox");
        job.backfill();
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_domain_event_outbox"));
    }

    @Test
    void lateUsageAndProjectionRebuildCannotResurrectDeletedStatistics() {
        close(); handler.onEvent(UserClosedEvent.initial(1L));
        TokenUsageEvent event = new TokenUsageEvent();
        event.setUserId(1L); event.setTenantId(20L); event.setApiKeyId(102L); event.setServiceId(202L);
        event.setModel("shared"); event.setStatus("SUCCESS"); event.setUsageSource("EXACT");
        event.setRequestStartedAt(Instant.now()); event.setCostResult(CostResult.of(CostStatus.UNPRICED));
        var consumer = transactional(new TokenUsageEventHandler(session.getMapper(TokenUsageMapper.class), session.getMapper(TokenUsageCostMapper.class), users));
        consumer.onEvent(event);
        transactional(new DurableTokenUsageEventPublisher(session.getMapper(TokenUsageOutboxMapper.class), new UsageOutboxProperties(), mock(UsageOutboxMetrics.class), users)).publish(event);
        new TransactionTemplate(manager).executeWithoutResult(ignored -> new UsageOutboxStager(session.getMapper(TokenUsageOutboxMapper.class), new UsageOutboxProperties(), users).stage(event, JSON.toJSONString(event), LocalDateTime.now()));
        transactional(new UsageProjectionServiceImpl(session.getMapper(TokenUsageHourlyAggMapper.class))).rebuild(bucket, bucket.plusHours(1));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_token_usage WHERE user_id=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_token_usage_outbox WHERE user_id=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM t_token_usage_hourly_agg WHERE user_id=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM t_token_usage_hourly_agg WHERE user_id=2"));
    }

    @Test
    void creationHoldingUserLockCompletesBeforeClosureAndIsThenCleaned() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creation = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(ignored -> {
                assertEquals(1L, keys.lockActiveKeyOwner(1L)); locked.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                jdbc.update("INSERT INTO t_api_key(api_key_id,user_id,tenant_id,api_key,key_prefix) VALUES(104,1,20,'new-key','sk_new')");
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> closure = executor.submit(this::close);
            release.countDown(); creation.get(5, TimeUnit.SECONDS); closure.get(5, TimeUnit.SECONDS);
            assertNull(keys.lockActiveKeyOwner(1L));
            assertNull(session.getMapper(UserTenantMapper.class).lockActiveJoiningUser(1L));
            handler.onEvent(UserClosedEvent.initial(1L));
            assertEquals(0, scalar("SELECT COUNT(*) FROM t_api_key WHERE user_id=1 AND status<>0"));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
