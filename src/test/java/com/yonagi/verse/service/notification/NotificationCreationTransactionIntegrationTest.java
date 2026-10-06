package com.yonagi.verse.service.notification;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.async.handler.NotificationEventHandler;
import com.yonagi.verse.common.cache.QueryCache;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.database.VerseMetaObjectHandler;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.NotificationRecipientMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.TenantSendNotificationReqDTO;
import com.yonagi.verse.dto.resp.NotificationInfoRespDTO;
import com.yonagi.verse.service.CurrentTenantStateService;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.TenantMediaService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.helper.TenantValidationHelper;
import com.yonagi.verse.service.impl.NotificationServiceImpl;
import com.yonagi.verse.service.impl.TenantCrudServiceImpl;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import com.yonagi.verse.support.MySqlTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用生产 Mapper、Spring 事务代理和隔离 MySQL 库验证实际提交与回滚。 */
class NotificationCreationTransactionIntegrationTest {
    private MySqlTestDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transaction;
    private NotificationMapper notifications;
    private SimpMessagingTemplate messaging;
    private NotificationService service;
    private NotificationEventHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        database = MySqlTestDatabase.create();
        jdbc = new JdbcTemplate(database);
        String schema;
        try (var input = new ClassPathResource("schema.sql").getInputStream()) {
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String table : List.of("t_notification", "t_notification_recipient")) {
            var ddl = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\(.*?;").matcher(schema);
            assertTrue(ddl.find(), table);
            jdbc.execute(ddl.group());
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var global = GlobalConfigUtils.defaults().setMetaObjectHandler(new VerseMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addMapper(NotificationMapper.class);
        configuration.addMapper(NotificationRecipientMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(database);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        SqlSessionTemplate sessions = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        notifications = sessions.getMapper(NotificationMapper.class);
        NotificationRecipientMapper recipients = sessions.getMapper(NotificationRecipientMapper.class);
        messaging = mock(SimpMessagingTemplate.class);
        transactionManager = new DataSourceTransactionManager(database);
        transaction = new TransactionTemplate(transactionManager);
        NotificationCreationService creation = transactional(new NotificationCreationService(notifications, recipients, messaging));
        var target = new NotificationServiceImpl(mock(TenantAccessPolicy.class), recipients,
                mock(DomainEventPublisher.class), mock(QueryCache.class), creation);
        service = transactional(target);
        handler = new NotificationEventHandler(creation);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulCreationPushesOnlyAfterOuterCommit(boolean consumer) {
        doAnswer(call -> {
            // 独立连接能看到全部数据，证明推送发生在真实数据库提交之后。
            assertEquals(1, committedCount("t_notification"));
            assertEquals(2, committedCount("t_notification_recipient"));
            assertNotNull(((NotificationInfoRespDTO) call.getArgument(2)).getCreateTime());
            return null;
        }).when(messaging).convertAndSendToUser(anyString(), eq("/queue/notifications"), any());
        transaction.executeWithoutResult(status -> {
            create(consumer, event());
            assertEquals(1, count("t_notification"));
            assertEquals(2, count("t_notification_recipient"));
            verifyNoInteractions(messaging);
        });
        verify(messaging, times(2)).convertAndSendToUser(anyString(), eq("/queue/notifications"), any());
        verify(messaging, times(2)).convertAndSendToUser(anyString(), eq("/queue/notifications/unread-count"), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outerRollbackDiscardsNotificationsAndNeverPushes(boolean consumer) {
        transaction.executeWithoutResult(status -> {
            create(consumer, event());
            assertEquals(1, count("t_notification"));
            status.setRollbackOnly();
        });
        assertEmptyAndNoPush();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void secondRecipientFailureRollsBackPreviouslyInsertedRowsAndAllowsRetry(boolean consumer) {
        NotificationEvent event = event();
        event.setRecipientUserIds(List.of(30L, 30L));
        assertThrows(DuplicateKeyException.class, () -> create(consumer, event));
        assertEmptyAndNoPush();
        event.setRecipientUserIds(List.of(30L, 40L));
        create(consumer, event);
        assertEquals(1, count("t_notification"));
        assertEquals(2, count("t_notification_recipient"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void notificationInsertFailurePropagatesWithoutRecipientsOrPush(boolean consumer) {
        NotificationEvent event = event();
        event.setTitle(null);
        assertThrows(DataIntegrityViolationException.class, () -> create(consumer, event));
        assertEmptyAndNoPush();
    }

    @Test
    void duplicateConsumerEventDoesNotDuplicateRowsOrPushes() {
        handler.onEvent(event());
        handler.onEvent(event());
        assertEquals(1, count("t_notification"));
        assertEquals(2, count("t_notification_recipient"));
        verify(messaging, times(4)).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void concurrentDuplicateConsumerEventsCommitOneCompleteNotification() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var tasks = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                handler.onEvent(event());
                return null;
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, count("t_notification"));
        assertEquals(2, count("t_notification_recipient"));
        verify(messaging, times(4)).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void pushFailureLeavesCommittedRowsAndSuccessfulCreation() {
        doThrow(new IllegalStateException("websocket unavailable")).when(messaging)
                .convertAndSendToUser(anyString(), anyString(), any());
        assertDoesNotThrow(() -> create(false, event()));
        assertEquals(1, committedCount("t_notification"));
        assertEquals(2, committedCount("t_notification_recipient"));
    }

    @Test
    void announcementPersistenceFailureReturnsDomainErrorWithCauseInsteadOfSuccess() {
        UserTenantService memberships = mock(UserTenantService.class);
        when(memberships.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        UserTenantDO member = new UserTenantDO();
        member.setUserId(30L);
        when(memberships.getTenantAllMembers(20L)).thenReturn(List.of(member, member));
        var tenant = new TenantCrudServiceImpl(mock(TenantAccessPolicy.class), mock(TenantMapper.class),
                memberships, mock(UserMapper.class), mock(StringRedisTemplate.class), mock(JwtUtil.class),
                service, mock(TenantValidationHelper.class), notifications, mock(TenantMediaService.class),
                mock(CurrentTenantStateService.class), mock(TenantActivityRecorder.class));
        ReflectionTestUtils.setField(tenant, "maxNotificationSendPerDay", 10);
        var request = new TenantSendNotificationReqDTO();
        request.setReceiverType(1);
        request.setSeverity("INFO");
        request.setTitle("标题");
        request.setContent("正文");
        ServerException failure = assertThrows(ServerException.class,
                () -> tenant.sendNotificationInTenant(10L, 20L, request));
        assertEquals(TenantErrorCodeEnum.TENANT_NOTIFICATION_PUSH_ERROR.code(), failure.getErrorCode());
        assertInstanceOf(DuplicateKeyException.class, failure.getCause());
        assertEmptyAndNoPush();
    }

    private <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T proxy = (T) factory.getProxy();
        return proxy;
    }

    private void create(boolean consumer, NotificationEvent event) {
        if (consumer) handler.onEvent(event);
        else service.createAndPush(event.getTenantId(), event.getType(), event.getSeverity(), event.getTitle(),
                event.getContent(), event.getSenderId(), event.getRecipientUserIds());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int committedCount(String table) {
        try (var connection = database.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private void assertEmptyAndNoPush() {
        assertEquals(0, count("t_notification"));
        assertEquals(0, count("t_notification_recipient"));
        verifyNoInteractions(messaging);
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setNotificationId(100L);
        event.setTenantId(20L);
        event.setType("ANNOUNCEMENT");
        event.setSeverity("INFO");
        event.setTitle("标题");
        event.setContent("正文");
        event.setSenderId(10L);
        event.setRecipientUserIds(List.of(30L, 40L));
        return event;
    }
}
