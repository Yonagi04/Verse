package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.CostLimitPatch;
import com.yonagi.verse.service.pricing.CostResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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

/** 显式指定测试服务后，按当前 schema 创建随机临时库；从不迁移或清理现有业务库。 */
@EnabledIfEnvironmentVariable(named = "VERSE_BUDGET_TEST_URL", matches = ".+")
class CostBudgetMySqlIntegrationTest {
    private DriverManagerDataSource server;
    private DriverManagerDataSource database;
    private String databaseName;
    private JdbcTemplate jdbc;
    private ApiKeyMapper keys;
    private CostBudgetSettlementMapper settlements;
    private CostBudgetInvocationMapper invocations;
    private CostBudgetPeriodMapper periods;
    private DataSourceTransactionManager manager;
    private CostBudgetService instanceA;
    private CostBudgetService instanceB;
    private BudgetExecutionRegistry registry;
    private UsageOutboxStager outbox;
    private final UserContext ctx = new UserContext().setUserId(1L).setCurrentTenantId(2L).setApiKeyId(3L);
    private final Instant now = Instant.parse("2026-09-30T10:00:00Z");

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("VERSE_BUDGET_TEST_URL");
        String user = System.getenv("VERSE_BUDGET_TEST_USER");
        String password = System.getenv("VERSE_BUDGET_TEST_PASSWORD");
        server = new DriverManagerDataSource(url, user, password);
        databaseName = "verse_budget_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(server).execute("CREATE DATABASE " + databaseName);
        String databaseUrl = url.contains("?") ? url.replace("?", "/" + databaseName + "?") : url + "/" + databaseName;
        database = new DriverManagerDataSource(databaseUrl, user, password);
        jdbc = new JdbcTemplate(database);
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));
        try (var connection = database.getConnection()) {
            // CI checkout 仅依赖已跟踪的当前 schema，不依赖本地增量 SQL。
            for (String name : List.of("t_api_key", "t_token_usage_outbox", "t_cost_budget_invocation",
                    "t_cost_budget_settlement", "t_cost_budget_period")) {
                ScriptUtils.executeSqlScript(connection,
                        new ByteArrayResource(table(schema, name).getBytes(StandardCharsets.UTF_8)));
            }
        }
        MybatisConfiguration config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(ApiKeyMapper.class, CostBudgetSettlementMapper.class,
                CostBudgetInvocationMapper.class, CostBudgetPeriodMapper.class, TokenUsageOutboxMapper.class)) config.addMapper(mapper);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(database); factory.setConfiguration(config);
        SqlSessionTemplate session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        keys = session.getMapper(ApiKeyMapper.class); settlements = session.getMapper(CostBudgetSettlementMapper.class);
        invocations = session.getMapper(CostBudgetInvocationMapper.class); periods = session.getMapper(CostBudgetPeriodMapper.class);
        manager = new DataSourceTransactionManager(database);
        registry = new BudgetExecutionRegistry(mock(RedissonClient.class));
        outbox = spy(new UsageOutboxStager(session.getMapper(TokenUsageOutboxMapper.class), new UsageOutboxProperties()));
        instanceA = service(registry); instanceB = service(new BudgetExecutionRegistry(mock(RedissonClient.class)));
        jdbc.update("INSERT INTO t_api_key(api_key_id,user_id,tenant_id,api_key,key_prefix,name,cost_data_state) "
                + "VALUES (3,1,2,'hash','sk_test','测试 Key','READY'),(5,4,2,'hash2','sk_other','其他 Key','READY')");
    }
    private CostBudgetService service(BudgetExecutionRegistry proof) {
        CostBudgetService result = new CostBudgetService(keys, invocations, settlements, periods,
                new BudgetPeriodResolver(Clock.fixed(now, ZoneOffset.UTC)), proof, outbox, new UsageOutboxProperties(), manager, mock(CostBudgetAudit.class));
        ReflectionTestUtils.setField(result, "costingEnabled", true);
        return result;
    }
    @AfterEach void cleanup() {
        if (server != null && databaseName != null && databaseName.matches("verse_budget_test_[0-9a-f]{32}")) {
            new JdbcTemplate(server).execute("DROP DATABASE " + databaseName);
        }
    }
    private static String table(String schema, String name) {
        var matcher = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS `?" + Pattern.quote(name) + "`?\\s*\\(.*?;").matcher(schema);
        assertTrue(matcher.find()); return matcher.group();
    }
    private void configure(String json) throws Exception {
        CostLimitPatch patch = new ObjectMapper().readValue(json, CostLimitPatch.class);
        new TransactionTemplate(manager).executeWithoutResult(ignored -> {
            ApiKeyDO key = instanceA.lockKey(2L, 3L); instanceA.merge(key, patch);
            keys.update(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(ApiKeyDO.class).eq(ApiKeyDO::getApiKeyId, 3L)
                    .set(ApiKeyDO::getCostLimitEnabled, key.getCostLimitEnabled())
                    .set(ApiKeyDO::getCostLimitDailyFen, key.getCostLimitDailyFen())
                    .set(ApiKeyDO::getCostLimitWeeklyFen, key.getCostLimitWeeklyFen())
                    .set(ApiKeyDO::getCostLimitMonthlyFen, key.getCostLimitMonthlyFen())
                    .set(ApiKeyDO::getCostConfigVersion, key.getCostConfigVersion()));
        });
    }
    private TokenUsageEvent event(String requestId, String amount, CostStatus status, Instant started) {
        TokenUsageEvent event = new TokenUsageEvent(); event.setTenantId(2L); event.setApiKeyId(3L); event.setUserId(1L);
        event.setServiceId(requestId.hashCode() % 2 == 0 ? 10L : 11L); event.setModel("模型"); event.setStatus("SUCCESS");
        event.setRequestId(requestId); event.setRequestStartedAt(started);
        event.setCostResult(new CostResult(status, amount == null ? null : new BigDecimal(amount))); return event;
    }
    private void call(String id, String amount, CostStatus status) {
        instanceA.begin(ctx, id, now); instanceA.sent(id); instanceA.settle(event(id, amount, status, now));
    }

    @Test void originalFractionalCostsAcrossModelsAndInstancesBlockAtEquality() throws Exception {
        configure("{\"enabled\":true,\"dailyLimitFen\":\"1\"}");
        for (int index = 0; index < 8; index++) call("small-" + index, "0.125", CostStatus.CALCULATED);
        var status = instanceB.status(2L, 3L, 1L);
        assertEquals("LIMITED", status.budgetState()); assertEquals("1", status.periods().getFirst().usedCostFen());
        assertEquals("8", status.periods().getFirst().calculatedCount());
        assertThrows(CostLimitExceededException.class, () -> instanceB.check(ctx));
        instanceB.check(new UserContext().setCurrentTenantId(2L).setApiKeyId(5L));
        configure("{\"dailyLimitFen\":\"2\",\"expectedVersion\":\"1\"}"); instanceB.check(ctx);
        configure("{\"enabled\":false}"); call("over", "10", CostStatus.CALCULATED);
        assertEquals("11", instanceB.status(2L, 3L, 1L).periods().getFirst().usedCostFen());
        configure("{\"enabled\":true}"); assertThrows(CostLimitExceededException.class, () -> instanceB.check(ctx));
    }
    @Test void idempotencyMissingPeriodAndRebuildNeverDoubleCount() {
        call("unique", "0.000000000000000001", CostStatus.CALCULATED);
        String id = jdbc.queryForObject("SELECT event_id FROM t_cost_budget_settlement", String.class);
        var payload = jdbc.queryForObject("SELECT event_payload_json FROM t_cost_budget_settlement", String.class);
        instanceA.settle(JSON.parseObject(payload, TokenUsageEvent.class)); instanceB.apply(id);
        jdbc.update("DELETE FROM t_cost_budget_period WHERE period_type='DAY'");
        instanceB.rebuild(2L, 3L); instanceA.rebuild(2L, 3L);
        var status = instanceB.status(2L, 3L, 1L);
        assertEquals("0.000000000000000001", status.periods().getFirst().usedCostFen());
        assertEquals("1", status.periods().getFirst().calculatedCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_token_usage_outbox", Integer.class));
    }
    @Test void phaseBFailureRollsBackAllThreePeriodsAndOutboxThenRecovers() throws Exception {
        configure("{\"enabled\":true,\"dailyLimitFen\":\"100\"}");
        doThrow(new IllegalStateException("injected failure")).when(outbox).stage(any(), any(), any());
        call("phase-b", "1.123456789012345678", CostStatus.CALCULATED);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_cost_budget_settlement WHERE budget_applied=0", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_token_usage_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_cost_budget_period WHERE used_cost_fen<>0", Integer.class));
        assertThrows(CostBudgetUnavailableException.class, () -> instanceB.check(ctx));
        doCallRealMethod().when(outbox).stage(any(), any(), any());
        instanceA.retryLocalSnapshots();
        assertEquals("1.123456789012345678", instanceB.status(2L, 3L, 1L).periods().getFirst().usedCostFen());
        instanceB.check(ctx);
    }
    @Test void originalPeriodLateEventsLatestRecoveryAndZeroUnpricedCounts() throws Exception {
        configure("{\"enabled\":false,\"weeklyLimitFen\":\"1\",\"monthlyLimitFen\":\"1\"}");
        call("free", "0", CostStatus.CALCULATED); call("unpriced", null, CostStatus.UNPRICED);
        call("unknown", null, CostStatus.UNCALCULABLE); call("failed", null, CostStatus.NOT_CHARGEABLE);
        Instant yesterday = Instant.parse("2026-09-29T15:59:59Z");
        instanceA.begin(ctx, "late", yesterday); instanceA.sent("late"); instanceA.settle(event("late", "2", CostStatus.CALCULATED, yesterday));
        configure("{\"enabled\":true}");
        var status = instanceB.status(2L, 3L, 1L);
        assertEquals("0", status.periods().getFirst().usedCostFen());
        assertEquals("1", status.periods().getFirst().calculatedCount());
        assertEquals("1", status.periods().getFirst().unpricedCount());
        assertEquals("2026-10-05T00:00+08:00", status.retryAt()); assertEquals("WEEK", status.limits().getFirst().period());
        assertEquals(2, status.limits().size());
    }
    @Test void creatorPermissionVersionAndUnknownOwnerFailClosed() throws Exception {
        assertThrows(ClientException.class, () -> instanceB.status(2L, 3L, 4L));
        assertThrows(ClientException.class, () -> instanceB.status(9L, 3L, 1L));
        configure("{\"enabled\":true,\"dailyLimitFen\":\"100\"}");
        assertThrows(ClientException.class, () -> configure("{\"dailyLimitFen\":\"101\",\"expectedVersion\":\"0\"}"));
        instanceA.begin(ctx, "orphan", now); instanceA.sent("orphan");
        instanceA.check(ctx); // 本地新鲜证明为合法并发。
        assertThrows(CostBudgetUnavailableException.class, () -> instanceB.check(ctx));
        instanceA.finalizing("orphan"); assertThrows(CostBudgetUnavailableException.class, () -> instanceA.check(ctx));
        configure("{\"enabled\":false}"); instanceB.check(ctx);
        assertThrows(ClientException.class, () -> configure("{\"enabled\":true}"));
    }
    @Test void concurrentInstancesApplyOneSnapshotOnce() throws Exception {
        call("concurrent", "3.125", CostStatus.CALCULATED);
        String id = jdbc.queryForObject("SELECT event_id FROM t_cost_budget_settlement", String.class);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> instanceA.apply(id)); Future<?> second = executor.submit(() -> instanceB.apply(id));
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertEquals("3.125", instanceB.status(2L, 3L, 1L).periods().getFirst().usedCostFen());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_cost_budget_settlement", Integer.class));
    }
}
