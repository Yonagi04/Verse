package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.LlmCostStatusRespDTO;
import com.yonagi.verse.service.pricing.*;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import com.yonagi.verse.support.MySqlTestDatabase;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 InnoDB 验证联合记账、配置事务、告警快照和发送资格。 */
class LlmCostBudgetMySqlIntegrationTest {
    private MySqlTestDatabase database;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private CostBudgetService service;
    private LlmCostBudgetLedger ledger;
    private LlmCostBudgetConfigurationService config;
    private CostAlertDeliveryGuard guard;
    private BudgetPeriodResolver time;
    private ReliableDomainEventPublisher publisher;
    private PricingConfigurationService pricing;
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-11T05:00:00Z");
    private final UserContext api = new UserContext().setUserId(1L).setCurrentTenantId(2L).setApiKeyId(3L);
    private final UserContext playground = new UserContext().setUserId(1L).setCurrentTenantId(2L);

    @BeforeEach void setup() throws Exception {
        database = MySqlTestDatabase.create(); jdbc = new JdbcTemplate(database);
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));
        var tables = List.of("t_user", "t_tenant", "t_user_tenant", "t_api_key", "t_llm_service", "t_llm_service_capability", "t_llm_service_pricing",
                "t_token_usage", "t_token_usage_cost", "t_token_usage_outbox", "t_cost_budget_invocation", "t_cost_budget_settlement", "t_cost_budget_period",
                "t_llm_cost_budget", "t_cost_budget_invocation_model", "t_llm_cost_alert_event", "t_llm_cost_alert_delivery",
                "t_notification", "t_notification_recipient", "t_sms_send_record", "t_email_send_record");
        try (var connection = database.getConnection()) { for (String name : tables) {
            var pattern = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS `?" + Pattern.quote(name) + "`?\\s*\\(.*?;").matcher(schema);
            assertTrue(pattern.find(), name); ScriptUtils.executeSqlScript(connection, new ByteArrayResource(pattern.group().getBytes(StandardCharsets.UTF_8)));
        } }
        var mybatis = new MybatisConfiguration(); mybatis.setMapUnderscoreToCamelCase(true);
        var global=com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults()
                .setMetaObjectHandler(new com.yonagi.verse.common.database.VerseMetaObjectHandler());
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(mybatis,global);
        for (var mapper : List.of(ApiKeyMapper.class, CostBudgetInvocationMapper.class, CostBudgetSettlementMapper.class,
                CostBudgetPeriodMapper.class, LlmCostBudgetMapper.class, LlmCostAlertEventMapper.class, LlmCostAlertDeliveryMapper.class,
                LlmServiceMapper.class, LlmServiceCapabilityMapper.class, LlmServicePricingMapper.class, LlmPricingPeakPeriodMapper.class,
                TokenUsageOutboxMapper.class, UserMapper.class, TenantMapper.class, UserTenantMapper.class,
                NotificationMapper.class, NotificationRecipientMapper.class, SmsSendRecordMapper.class, EmailSendRecordMapper.class)) mybatis.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(database); factory.setConfiguration(mybatis);
        factory.setGlobalConfig(global);
        sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())); manager = new DataSourceTransactionManager(database); tx = new TransactionTemplate(manager);
        time = new BudgetPeriodResolver(Clock.fixed(now, ZoneOffset.UTC));
        var registry = new BudgetExecutionRegistry(mock(RedissonClient.class)); publisher = mock(ReliableDomainEventPublisher.class);
        var capture = new LlmCostAlertCaptureService(map(LlmCostAlertEventMapper.class), map(LlmCostAlertDeliveryMapper.class), map(LlmServiceMapper.class), publisher, time);
        ledger = new LlmCostBudgetLedger(map(LlmCostBudgetMapper.class), map(CostBudgetSettlementMapper.class), map(CostBudgetPeriodMapper.class), registry, time, new UsageOutboxProperties(), capture);
        ReflectionTestUtils.setField(ledger, "costingEnabled", true);
        var outbox = new UsageOutboxStager(map(TokenUsageOutboxMapper.class), new UsageOutboxProperties(), map(UserMapper.class));
        service = new CostBudgetService(map(ApiKeyMapper.class), map(CostBudgetInvocationMapper.class), map(CostBudgetSettlementMapper.class), map(CostBudgetPeriodMapper.class), time, registry, outbox, new UsageOutboxProperties(), manager, ledger, com.yonagi.verse.support.BudgetTestSupport.disabledTenantLedger(), org.mockito.Mockito.mock(com.yonagi.verse.dao.mapper.UserMapper.class));
        ReflectionTestUtils.setField(service, "costingEnabled", true);
        pricing = new PricingConfigurationService(map(LlmServicePricingMapper.class), map(LlmPricingPeakPeriodMapper.class), map(LlmServiceMapper.class), mock(PricingResolver.class));
        config = new LlmCostBudgetConfigurationService(map(LlmCostBudgetMapper.class), map(LlmServiceMapper.class), pricing,
                new TenantAccessPolicy(map(TenantMapper.class), map(UserTenantMapper.class)), ledger, time, map(UserMapper.class));
        guard = new CostAlertDeliveryGuard(ledger, mock(TenantCostBudgetLedger.class), map(TenantMapper.class), map(LlmCostAlertEventMapper.class), map(LlmCostAlertDeliveryMapper.class), map(UserMapper.class), time);
        jdbc.update("INSERT INTO t_user(user_id,username,nickname,password,email,email_hash) VALUES (1,'owner','管理员','test','encrypted','same-email'),(4,'member','成员','test','encrypted','same-email'),(5,'admin','管理员2','test','encrypted','same-email')");
        jdbc.update("INSERT INTO t_tenant(tenant_id,name,type,owner_id,activity_recording_enabled) VALUES(2,'测试团队','TEAM',1,1)");
        jdbc.update("INSERT INTO t_user_tenant(user_id,tenant_id,role) VALUES(1,2,'SUPER_ADMIN'),(4,2,'MEMBER'),(5,2,'ADMIN')");
        jdbc.update("INSERT INTO t_api_key(api_key_id,user_id,tenant_id,api_key,key_prefix) VALUES(3,1,2,'test-hash','sk_test')");
        for (long id : List.of(10L,11L)) {
            jdbc.update("INSERT INTO t_llm_service(service_id,tenant_id,name,provider,created_by) VALUES(?,2,?,'openai',1)", id, "模型" + id);
            jdbc.update("INSERT INTO t_llm_cost_budget(id,tenant_id,service_id,warning_channels,create_time,update_time) VALUES(?,2,?,'[]',NOW(6),NOW(6))", id, id);
            jdbc.update("INSERT INTO t_llm_service_pricing(pricing_id,tenant_id,service_id,billing_mode,currency,base_request_price_fen,effective_from,created_by) VALUES(?,2,?,'REQUEST','CNY',0,NOW(),1)", id, id);
        }
    }
    private <T> T map(Class<T> type) { return sql.getMapper(type); }
    @AfterEach void close() { if (database != null) database.close(); }
    private void budget(long model, String limit, String warning) throws Exception {
        var patch = json.readValue("{\"costLimit\":{\"expectedVersion\":\"" + version(model) + "\",\"enabled\":true,\"dailyLimitFen\":\"" + limit + "\"}" + (warning == null ? "" : ",\"costWarning\":" + warning) + "}", LlmCostBudgetUpdateReqDTO.class);
        tx.executeWithoutResult(ignored -> config.update(1L,2L,model,patch));
    }
    private long version(long model) { return jdbc.queryForObject("SELECT config_version FROM t_llm_cost_budget WHERE service_id=?", Long.class,model); }
    private TokenUsageEvent fact(String id, Long model, UserContext ctx, String amount) {
        var event = new TokenUsageEvent(); event.setTenantId(2L); event.setUserId(1L); event.setApiKeyId(ctx.getApiKeyId());
        event.setSource(ctx.getApiKeyId() == null ? "PLAYGROUND" : "API_KEY"); event.setServiceId(model); event.setRequestId(id);
        event.setRequestStartedAt(now); event.setStatus("SUCCESS"); event.setModel("模型"); event.setCostResult(new CostResult(CostStatus.CALCULATED,new BigDecimal(amount))); return event;
    }
    private void call(String id, Long model, UserContext ctx, String amount) {
        service.begin(ctx,model,id,now); service.permit(ctx,model,id); service.settle(fact(id,model,ctx,amount));
    }
    private BigDecimal used(String scope,long id) { return jdbc.queryForObject("SELECT used_cost_fen FROM t_cost_budget_period WHERE scope_type=? AND scope_id=? AND period_type='DAY'", BigDecimal.class,scope,id); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @RepeatedTest(5) void sameAdministratorCanCreateTwoModelsWhileBothHoldPermissionLocks() throws Exception {
        var users = map(UserMapper.class);
        var beforeUserLock = new CyclicBarrier(2);
        var coordinatedUsers = mock(UserMapper.class, invocation -> {
            // 两个事务均已完成权限当前读，再同时申请创建流程的用户锁。
            if (invocation.getMethod().getName().startsWith("selectActiveUserFor")) {
                beforeUserLock.await(5, TimeUnit.SECONDS);
            }
            return org.mockito.AdditionalAnswers.delegatesTo(users).answer(invocation);
        });
        var models = modelManagement(coordinatedUsers);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tx.execute(ignored -> models.addLlmService(1L, 2L, newModel("concurrent-a"))));
            var second = pool.submit(() -> tx.execute(ignored -> models.addLlmService(1L, 2L, newModel("concurrent-b"))));
            assertEquals(Boolean.TRUE, first.get(10, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, second.get(10, TimeUnit.SECONDS));
            assertEquals(4, count("t_llm_service"));
            assertEquals(4, count("t_llm_cost_budget"));
            assertEquals(2, count("t_llm_service_capability"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void modelCreationStillRejectsMembersAndClosedAccounts() {
        var models = modelManagement(map(UserMapper.class));
        assertThrows(ClientException.class, () -> tx.execute(ignored -> models.addLlmService(4L, 2L, newModel("member-model"))));
        jdbc.update("UPDATE t_user SET status=2,del_flag=1 WHERE user_id=1");
        assertThrows(ClientException.class, () -> tx.execute(ignored -> models.addLlmService(1L, 2L, newModel("closed-model"))));
        assertEquals(2, count("t_llm_service"));
        assertEquals(2, count("t_llm_cost_budget"));
    }

    @Test void creationUserLockKeepsAccountCloseOutsideCreationTransaction() throws Exception {
        var models = modelManagement(map(UserMapper.class));
        var metadata = (LlmMetadataService) ReflectionTestUtils.getField(models, "metadataService");
        var creationPaused = new CountDownLatch(1);
        var resumeCreation = new CountDownLatch(1);
        var closingStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            creationPaused.countDown();
            assertTrue(resumeCreation.await(5, TimeUnit.SECONDS));
            return null;
        }).when(metadata).replaceTags(anyLong(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var creating = pool.submit(() -> tx.execute(ignored -> models.addLlmService(1L, 2L, newModel("before-close"))));
            assertTrue(creationPaused.await(5, TimeUnit.SECONDS));
            var closing = pool.submit(() -> tx.execute(ignored -> {
                closingStarted.countDown();
                return jdbc.update("UPDATE t_user SET status=2,del_flag=1 WHERE user_id=1");
            }));
            assertTrue(closingStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> closing.get(200, TimeUnit.MILLISECONDS));
            resumeCreation.countDown();
            assertEquals(Boolean.TRUE, creating.get(10, TimeUnit.SECONDS));
            assertEquals(1, closing.get(10, TimeUnit.SECONDS));
            assertThrows(ClientException.class, () -> tx.execute(ignored -> models.addLlmService(1L, 2L, newModel("after-close"))));
            assertEquals(3, count("t_llm_service"));
            assertEquals(3, count("t_llm_cost_budget"));
        } finally {
            resumeCreation.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private com.yonagi.verse.service.impl.LlmManageServiceImpl modelManagement(UserMapper users) {
        var redis = mock(RedissonClient.class);
        var lock = mock(org.redisson.api.RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        try { when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
        var aes = mock(com.yonagi.verse.common.util.AesUtil.class);
        when(aes.encrypt(anyString())).thenReturn("encrypted");
        var models = new com.yonagi.verse.service.impl.LlmManageServiceImpl(
                new TenantAccessPolicy(map(TenantMapper.class), map(UserTenantMapper.class)), map(TenantMapper.class),
                mock(com.yonagi.verse.service.UserTenantService.class), aes,
                mock(org.springframework.data.redis.core.StringRedisTemplate.class), users,
                mock(com.yonagi.verse.common.security.JwtUtil.class), redis, mock(LlmMetadataService.class), pricing,
                mock(com.yonagi.verse.async.activity.TenantActivityRecorder.class), map(LlmServiceCapabilityMapper.class));
        ReflectionTestUtils.setField(models, "baseMapper", map(LlmServiceMapper.class));
        models.setCostConfiguration(config);
        return models;
    }

    private LlmServiceAddReqDTO newModel(String name) {
        var request = new LlmServiceAddReqDTO();
        request.setName(name); request.setProvider("openai"); request.setApiUrl("https://example.invalid/v1");
        request.setApiKey("test-key"); request.setModelName("test-model");
        return request;
    }

    @Test void queuedHeadDoesNotStarveTailAndIsRevisitedDespiteNewArrivals() throws Exception {
        String eventId = alertWithEmptyDeliveries();
        for (int i = 1; i <= 100; i++) {
            addDelivery(eventId, "EMAIL", i, "QUEUED");
            jdbc.update("INSERT INTO t_email_send_record(request_id,request_fingerprint,scene,provider,recipient_encrypted,recipient_hash,status,create_time,update_time,subject_hash) "
                    + "VALUES(?,'fixture','LLM_COST_LIMIT','fixture','encrypted','fixture',?,NOW(3),NOW(3),'fixture')",
                    "poll-EMAIL-" + i, i % 2 == 0 ? "SUBMITTING" : "QUEUED");
        }
        addDelivery(eventId, "ACTIVITY", 101, "PENDING");
        var activities = mock(com.yonagi.verse.async.activity.TenantActivityRecorder.class);
        var delivery = alertDelivery(activities);
        delivery.scan();
        assertEquals("PENDING", deliveryStatus("ACTIVITY", 101));
        verifyNoInteractions(activities);
        // 新任务不能无限延长本轮；完成本轮后必须回访队首以镜像终态。
        addDelivery(eventId, "ACTIVITY", 102, "PENDING");
        delivery.scan();
        assertEquals("ACCEPTED", deliveryStatus("ACTIVITY", 101));
        assertEquals("PENDING", deliveryStatus("ACTIVITY", 102));
        jdbc.update("UPDATE t_email_send_record SET status='ACCEPTED' WHERE request_id='poll-EMAIL-1'");
        delivery.scan();
        assertEquals("ACCEPTED", deliveryStatus("EMAIL", 1));
        delivery.scan();
        assertEquals("ACCEPTED", deliveryStatus("ACTIVITY", 102));
        verify(activities, times(2)).recordSystemSnapshot(any());
        assertEquals(100, count("t_email_send_record"));
    }

    @Test void failingHeadDoesNotStarveTailAndRemainsRetryable() throws Exception {
        String eventId = alertWithEmptyDeliveries();
        for (int i = 1; i <= 100; i++) addDelivery("missing-event", "EMAIL", i, "PENDING");
        addDelivery(eventId, "ACTIVITY", 101, "PENDING");
        var activities = mock(com.yonagi.verse.async.activity.TenantActivityRecorder.class);
        var delivery = alertDelivery(activities);
        delivery.scan();
        assertEquals("PENDING", deliveryStatus("ACTIVITY", 101));
        delivery.scan();
        assertEquals("ACCEPTED", deliveryStatus("ACTIVITY", 101));
        verify(activities).recordSystemSnapshot(any());
        jdbc.update("UPDATE t_llm_cost_alert_delivery SET event_id=?,channel='ACTIVITY' WHERE event_id='missing-event'", eventId);
        delivery.scan();
        assertEquals(101, jdbc.queryForObject("SELECT COUNT(*) FROM t_llm_cost_alert_delivery WHERE status='ACCEPTED'", Integer.class));
        verify(activities, times(101)).recordSystemSnapshot(any());
    }

    @Test void pollingHonorsRetryTimesAndLeasesAndRecoversAfterEmptyQueue() throws Exception {
        var activities = mock(com.yonagi.verse.async.activity.TenantActivityRecorder.class);
        var delivery = alertDelivery(activities);
        delivery.scan();
        String eventId = alertWithEmptyDeliveries();
        addDelivery(eventId, "ACTIVITY", 1, "RETRY_WAIT");
        addDelivery(eventId, "ACTIVITY", 2, "CLAIMED");
        addDelivery(eventId, "ACTIVITY", 3, "CLAIMED");
        addDelivery(eventId, "ACTIVITY", 4, "RETRY_WAIT");
        jdbc.update("UPDATE t_llm_cost_alert_delivery SET next_retry_at=? WHERE recipient_user_id=1", time.local(now).plusMinutes(1));
        jdbc.update("UPDATE t_llm_cost_alert_delivery SET lease_until=? WHERE recipient_user_id=2", time.local(now).plusMinutes(1));
        jdbc.update("UPDATE t_llm_cost_alert_delivery SET lease_until=? WHERE recipient_user_id=3", time.local(now).minusMinutes(1));
        delivery.scan();
        assertEquals("RETRY_WAIT", deliveryStatus("ACTIVITY", 1));
        assertEquals("CLAIMED", deliveryStatus("ACTIVITY", 2));
        assertEquals("ACCEPTED", deliveryStatus("ACTIVITY", 3));
        assertEquals("ACCEPTED", deliveryStatus("ACTIVITY", 4));
        jdbc.update("UPDATE t_llm_cost_alert_delivery SET next_retry_at=?,lease_until=? WHERE recipient_user_id IN (1,2)", time.local(now).minusMinutes(1), time.local(now).minusMinutes(1));
        delivery.scan();
        assertEquals("ACCEPTED", deliveryStatus("ACTIVITY", 1));
        assertEquals("ACCEPTED", deliveryStatus("ACTIVITY", 2));
        verify(activities, times(4)).recordSystemSnapshot(any());
    }

    private String alertWithEmptyDeliveries() throws Exception {
        budget(10, "1", null); call("polling", 10L, playground, "1");
        jdbc.update("DELETE FROM t_llm_cost_alert_delivery");
        return jdbc.queryForObject("SELECT event_id FROM t_llm_cost_alert_event", String.class);
    }

    private void addDelivery(String eventId, String channel, long recipient, String status) {
        jdbc.update("INSERT INTO t_llm_cost_alert_delivery(event_id,channel,recipient_user_id,request_id,status,next_retry_at,create_time,update_time) VALUES(?,?,?,?,?,?,?,?)",
                eventId, channel, recipient, "poll-" + channel + "-" + recipient, status, time.local(now), time.local(now), time.local(now));
    }

    private String deliveryStatus(String channel, long recipient) {
        return jdbc.queryForObject("SELECT status FROM t_llm_cost_alert_delivery WHERE channel=? AND recipient_user_id=?", String.class, channel, recipient);
    }

    private LlmCostAlertDeliveryService alertDelivery(com.yonagi.verse.async.activity.TenantActivityRecorder activities) {
        return new LlmCostAlertDeliveryService(map(LlmCostAlertEventMapper.class), map(LlmCostAlertDeliveryMapper.class), ledger, guard,
                mock(com.yonagi.verse.service.notification.NotificationCreationService.class), activities,
                mock(com.yonagi.verse.service.messaging.SmsSendService.class), mock(com.yonagi.verse.service.messaging.EmailSendService.class),
                map(SmsSendRecordMapper.class), map(EmailSendRecordMapper.class), new com.yonagi.verse.common.config.MessagingProperties(),
                mock(com.yonagi.verse.common.util.AesUtil.class), time, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), manager);
    }

    @Test void apiAndPlaygroundSettleOnceButKeyOnlyCountsApi() throws Exception {
        budget(10,"1", "{\"enabled\":true,\"thresholdPercent\":50,\"channels\":[\"EMAIL\",\"IN_APP\"]}");
        call("api",10L,api,"0.5"); call("pg",10L,playground,"0.5"); service.settle(fact("pg",10L,playground,"0.5"));
        assertEquals(0,new BigDecimal("1").compareTo(used("LLM",10))); assertEquals(0,new BigDecimal("0.5").compareTo(used("API_KEY",3)));
        assertEquals(2,count("t_cost_budget_settlement")); assertEquals(2,count("t_token_usage_outbox"));
        assertEquals(2,count("t_llm_cost_alert_event"));
        assertThrows(CostLimitExceededException.class, () -> service.check(playground,10L));
        service.check(playground,11L);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_llm_cost_alert_delivery WHERE channel='EMAIL' AND status='SKIPPED' AND reason_code='SKIPPED_DUPLICATE_CONTACT' AND event_id=(SELECT event_id FROM t_llm_cost_alert_event WHERE event_type='LIMIT')",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_llm_cost_alert_delivery WHERE channel='ACTIVITY' AND status='PENDING'",Integer.class));
    }
    @Test void fallbackUsesActualModelAndUnknownFencesAllParticipants() throws Exception {
        budget(10,"10",null); budget(11,"10",null);
        service.begin(playground,10L,"fallback",now); service.permit(playground,10L,"fallback"); service.permit(playground,11L,"fallback");
        service.settle(fact("fallback",11L,playground,"2"));
        assertEquals(0,BigDecimal.ZERO.compareTo(used("LLM",10))); assertEquals(0,new BigDecimal("2").compareTo(used("LLM",11)));
        assertEquals(2,count("t_cost_budget_invocation_model"));
        service.begin(playground,10L,"uncertain",now); service.permit(playground,10L,"uncertain"); service.permit(playground,11L,"uncertain");
        var unknown = fact("uncertain",11L,playground,"0.1"); unknown.setExecutionOutcome(com.yonagi.verse.service.forward.UpstreamExecutionOutcome.UNKNOWN); service.settle(unknown);
        assertThrows(CostBudgetUnavailableException.class, () -> service.check(playground,10L)); assertThrows(CostBudgetUnavailableException.class, () -> service.check(playground,11L));
    }
    @Test void fullAndSummaryUseCurrentTargetRoleAndBatchStaysSeparated() throws Exception {
        budget(10,"20",null);
        var full = tx.execute(ignored -> config.status(1L,2L,10L)); assertInstanceOf(LlmCostStatusRespDTO.Full.class,full);
        jdbc.update("UPDATE t_user_tenant SET role='MEMBER' WHERE user_id=1");
        var batch = tx.execute(ignored -> config.statuses(1L,2L,List.of(11L,10L,10L)));
        assertEquals(2,batch.items().size()); assertTrue(batch.items().stream().allMatch(LlmCostStatusRespDTO.Summary.class::isInstance));
        assertFalse(JSON.toJSONString(batch).contains("usedCostFen"));
        assertThrows(ClientException.class, () -> tx.execute(ignored -> config.status(1L,99L,10L)));
    }
    @Test void configurationIsVersionedAndClosingBudgetKeepsWarningDraft() throws Exception {
        budget(10,"20","{\"enabled\":true,\"channels\":[\"SMS\"]}"); long version=version(10);
        var stale=json.readValue("{\"costLimit\":{\"expectedVersion\":\"0\",\"enabled\":false}}",LlmCostBudgetUpdateReqDTO.class);
        assertThrows(ClientException.class, () -> tx.execute(ignored -> config.update(1L,2L,10L,stale)));
        var off=json.readValue("{\"costLimit\":{\"expectedVersion\":\""+version+"\",\"enabled\":false}}",LlmCostBudgetUpdateReqDTO.class);
        var result=(LlmCostStatusRespDTO.Full)tx.execute(ignored -> config.update(1L,2L,10L,off));
        assertTrue(result.costWarning().enabled()); assertFalse(result.costWarning().effective());
        jdbc.update("UPDATE t_llm_service SET status=0 WHERE service_id=10");
        assertInstanceOf(LlmCostStatusRespDTO.Full.class,tx.execute(ignored -> config.status(1L,2L,10L)));
    }
    @Test void sendingRechecksContactAndDisablingBudgetCancelsPending() throws Exception {
        budget(10,"1",null); call("limit",10L,playground,"1");
        var email=jdbc.queryForObject("SELECT request_id FROM t_llm_cost_alert_delivery WHERE channel='EMAIL' AND status='PENDING'",String.class);
        assertNull(tx.execute(ignored -> guard.verify("LLM_COST_LIMIT",email)));
        jdbc.update("UPDATE t_user SET email_hash='new-contact' WHERE user_id=1");
        assertEquals(MessageSubmissionStatus.CANCELLED,tx.execute(ignored -> guard.verify("LLM_COST_LIMIT",email)).status());
        jdbc.update("UPDATE t_llm_cost_budget SET cost_limit_enabled=0 WHERE service_id=10");
        assertEquals("BUDGET_DISABLED",tx.execute(ignored -> guard.verify("LLM_COST_LIMIT",email)).errorCode());
        jdbc.update("UPDATE t_llm_cost_budget SET cost_limit_enabled=1 WHERE service_id=10");
        tx.executeWithoutResult(ignored -> ledger.capture(ledger.lock(2L,10L)));
        assertEquals(1,count("t_llm_cost_alert_event"));
    }
    @Test void limitCancelsUnclaimedWarningButLeavesClaimedResultToTheSendGuard() throws Exception {
        budget(10,"1","{\"enabled\":true,\"thresholdPercent\":50,\"channels\":[\"EMAIL\",\"IN_APP\"]}");
        call("warning",10L,playground,"0.5");
        var claimed=jdbc.queryForObject("SELECT request_id FROM t_llm_cost_alert_delivery WHERE channel='EMAIL' AND status='PENDING'",String.class);
        jdbc.update("UPDATE t_llm_cost_alert_delivery SET status='CLAIMED',execution_token='fixture',lease_until=? WHERE request_id=?",time.local(now).plusSeconds(30),claimed);
        call("limit-after-warning",10L,playground,"0.5");
        assertEquals("CLAIMED",jdbc.queryForObject("SELECT status FROM t_llm_cost_alert_delivery WHERE request_id=?",String.class,claimed));
        var denied=tx.execute(ignored -> guard.verify("LLM_COST_WARNING",claimed));
        assertEquals(MessageSubmissionStatus.CANCELLED,denied.status()); assertEquals("STATE_CHANGED",denied.errorCode());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM t_llm_cost_alert_delivery d JOIN t_llm_cost_alert_event e ON e.event_id=d.event_id WHERE e.event_type='WARNING' AND d.channel='IN_APP' AND d.status='CANCELLED'",Integer.class));
    }
    @Test void priceAndBudgetCloseAtomicallyAndUnknownAllowsWarningOffAndMetadataEdit() throws Exception {
        budget(10,"20","{\"enabled\":true,\"channels\":[\"EMAIL\"]}"); long originalVersion=version(10);
        var priceOff=json.readValue("{\"enabled\":false}",PricingConfigReqDTO.class);
        assertThrows(ClientException.class,() -> tx.executeWithoutResult(ignored ->
                config.mergeForEdit(1L,config.lockService(2L,10L),null,null,priceOff)));
        assertEquals(originalVersion,version(10)); assertEquals(Boolean.TRUE,tx.execute(ignored -> pricing.eligible(2L,10L)));
        jdbc.update("UPDATE t_llm_cost_budget SET cost_data_reason='SNAPSHOT_CONFLICT' WHERE service_id=10");
        tx.executeWithoutResult(ignored -> config.mergeForEdit(1L,config.lockService(2L,10L),null,null,null));
        var warningOff=json.readValue("{\"costLimit\":{\"expectedVersion\":\""+version(10)+"\"},\"costWarning\":{\"enabled\":false}}",LlmCostBudgetUpdateReqDTO.class);
        tx.executeWithoutResult(ignored -> config.update(1L,2L,10L,warningOff));
        var budgetOff=json.readValue("{\"expectedVersion\":\""+version(10)+"\",\"enabled\":false}",CostLimitPatch.class);
        tx.executeWithoutResult(ignored -> {
            var model=config.lockService(2L,10L); config.mergeForEdit(1L,model,budgetOff,null,priceOff);
            pricing.replace(1L,model,priceOff);
        });
        assertEquals(Boolean.FALSE,tx.execute(ignored -> pricing.eligible(2L,10L)));
        assertEquals(0,jdbc.queryForObject("SELECT cost_limit_enabled FROM t_llm_cost_budget WHERE service_id=10",Integer.class));
        assertThrows(ClientException.class,() -> budget(10,"20",null));
    }
    @Test void deliveryCreatesOneAnnouncementAndSystemEventAndMirrorsOneExternalSend() throws Exception {
        var key=Base64.getEncoder().encodeToString(new byte[32]); var aes=new com.yonagi.verse.common.util.AesUtil(key,key);
        jdbc.update("UPDATE t_user SET email=?",aes.encrypt("admin@example.test"));
        budget(10,"1",null); call("dispatch",10L,playground,"1");
        jdbc.update("UPDATE t_tenant SET activity_recording_enabled=0 WHERE tenant_id=2");
        var settings=new com.yonagi.verse.common.config.MessagingProperties(); settings.getEmail().setProvider("mailpit");
        var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var records=new com.yonagi.verse.service.messaging.SendRecordStore(map(SmsSendRecordMapper.class),map(EmailSendRecordMapper.class),aes,manager);
        var emailProvider=mock(com.yonagi.verse.common.messaging.provider.EmailProvider.class);
        var smsProvider=mock(com.yonagi.verse.common.messaging.provider.SmsProvider.class);
        when(emailProvider.name()).thenReturn("fixture"); when(smsProvider.name()).thenReturn("fixture");
        when(emailProvider.send(any())).thenReturn(com.yonagi.verse.dto.resp.MessageSubmissionRespDTO.accepted("r","m"));
        var notification=new com.yonagi.verse.service.notification.NotificationCreationService(map(NotificationMapper.class),map(NotificationRecipientMapper.class),mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
        var activities=mock(com.yonagi.verse.async.activity.TenantActivityRecorder.class);
        var delivery=new LlmCostAlertDeliveryService(map(LlmCostAlertEventMapper.class),map(LlmCostAlertDeliveryMapper.class),ledger,guard,
                notification,activities,new com.yonagi.verse.service.messaging.SmsSendService(smsProvider,records,metrics,settings),
                new com.yonagi.verse.service.messaging.EmailSendService(emailProvider,records,metrics,settings),map(SmsSendRecordMapper.class),map(EmailSendRecordMapper.class),settings,aes,time,metrics,manager);
        delivery.wake(jdbc.queryForObject("SELECT event_id FROM t_llm_cost_alert_event",String.class)); delivery.scan();
        assertEquals(1,count("t_notification")); assertEquals(3,count("t_notification_recipient"));
        assertEquals(1,count("t_email_send_record")); assertEquals(0,count("t_sms_send_record"));
        verify(emailProvider,never()).send(any()); verify(activities,times(1)).recordSystemSnapshot(any());
        var worker=new com.yonagi.verse.async.messaging.MessageDeliveryWorker(records,smsProvider,emailProvider,
                mock(com.yonagi.verse.service.messaging.VerificationCodeStore.class),settings,metrics);
        worker.setEligibility(List.of((scene,id) -> tx.execute(ignored -> guard.verify(scene,id))));
        worker.execute(MessageChannel.EMAIL,jdbc.queryForObject("SELECT id FROM t_email_send_record",Long.class));
        delivery.scan(); delivery.scan();
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT status FROM t_llm_cost_alert_delivery WHERE channel='EMAIL' AND canonical_delivery_id IS NULL AND recipient_user_id=1",String.class));
        verify(emailProvider,times(1)).send(any()); assertEquals(1,count("t_notification"));
        assertFalse(jdbc.queryForObject("SELECT content FROM t_notification",String.class).contains("¥"));
    }


    @Test void preparingStreamDoesNotClaimAnUnsentUpstream() {
        service.begin(playground,10L,"prepared-only",now);
        service.prepareStream(playground,10L,"prepared-only");
        assertFalse(service.hasUpstream("prepared-only"));
        service.abandon(playground,"prepared-only");
        assertEquals("NO_UPSTREAM",jdbc.queryForObject("SELECT state FROM t_cost_budget_invocation WHERE request_id='prepared-only'",String.class));
        assertEquals(0,count("t_cost_budget_settlement"));
    }

    @Test void notificationReadinessCountsContactsWithoutLoadingUserRecords() {
        var summary=map(LlmCostBudgetMapper.class).administratorContacts(2L);
        assertEquals(2,summary.total()); assertEquals(2,summary.missingPhoneCount()); assertEquals(0,summary.missingEmailCount());
    }
}
