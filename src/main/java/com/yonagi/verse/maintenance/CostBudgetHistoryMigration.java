package com.yonagi.verse.maintenance;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import com.yonagi.verse.service.budget.CostBudgetSnapshot;
import com.yonagi.verse.service.pricing.CostResult;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 停应用后一次完成历史导入和两类预算初始化；不启动 Spring、恢复作业或消息消费者。 */
public final class CostBudgetHistoryMigration {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BudgetPeriodResolver time = new BudgetPeriodResolver();

    public CostBudgetHistoryMigration(DataSource source) {
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    public static void main(String[] args) {
        if (args.length != 1 || !("--check".equals(args[0]) || "--apply".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: CostBudgetHistoryMigration --check|--apply");
        }
        var source = new DriverManagerDataSource(required("VERSE_MIGRATION_URL"),
                required("VERSE_MIGRATION_USER"), required("VERSE_MIGRATION_PASSWORD"));
        var migration = new CostBudgetHistoryMigration(source);
        boolean apply = "--apply".equals(args[0]);
        long count = migration.migrate(Instant.now(), apply);
        if (apply) migration.finishSchemaUpgrade();
        System.out.printf("%s: %d current-period ledger facts; API Key and LLM periods rebuilt%s%n",
                apply ? "COMMITTED" : "CHECKED", count, apply ? "" : " (transaction rolled back)");
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null) throw new IllegalArgumentException("Missing environment variable: " + key);
        return value;
    }

    /** check 与 apply 使用相同校验；失败或 check 均回滚全部数据，不留下半迁移状态。 */
    public long migrate(Instant instant, boolean apply) {
        LocalDateTime now = time.local(instant);
        LocalDateTime start = time.earliest(instant);
        return Objects.requireNonNull(transaction.execute(status -> {
            // 与在线结算相同的锁序；维护期间所有应用写入者仍须停止。
            jdbc.queryForList("SELECT id FROM t_api_key ORDER BY tenant_id,api_key_id FOR UPDATE");
            jdbc.queryForList("SELECT id FROM t_llm_cost_budget ORDER BY tenant_id,service_id FOR UPDATE");
            requireZero("SELECT COUNT(*) FROM t_cost_budget_invocation WHERE state NOT IN ('SETTLED','NO_UPSTREAM')",
                    "unfinished invocation: stop calls and recover the original execution first");
            requireNoFault("t_api_key");
            requireNoFault("t_llm_cost_budget");
            requireZero("SELECT COUNT(*) FROM t_token_usage WHERE request_started_at IS NULL AND create_time>=?",
                    "current history has no original request start time", start);
            requireZero("SELECT COUNT(*) FROM t_token_usage u LEFT JOIN t_token_usage_cost c ON c.usage_id=u.id WHERE u.request_started_at>=? AND c.id IS NULL",
                    "usage history is missing its cost snapshot; finish the usage migration first", start);

            page("t_token_usage", "request_started_at>=?", List.of(start), id -> importUsage(id, now));
            page("t_token_usage_outbox", "1=1", List.of(), id -> {
                var event = JSON.parseObject(jdbc.queryForObject(
                        "SELECT payload_json FROM t_token_usage_outbox WHERE id=?", String.class, id), TokenUsageEvent.class);
                if (event.getRequestStartedAt() == null) throw new IllegalStateException("Outbox has no original request start: " + id);
                if (!time.local(event.getRequestStartedAt()).isBefore(start)) store(event, now);
            });
            // 账本可能已经落盘而费用消费者尚未落事实，仍需校验其原始快照后参与重建。
            page("t_cost_budget_settlement", "request_started_at>=?", List.of(start), id -> store(
                    JSON.parseObject(jdbc.queryForObject("SELECT event_payload_json FROM t_cost_budget_settlement WHERE id=?",
                            String.class, id), TokenUsageEvent.class), now));

            jdbc.update("""
                    INSERT INTO t_llm_cost_budget(id,tenant_id,service_id,warning_channels,create_time,update_time)
                    SELECT s.id,s.tenant_id,s.service_id,JSON_ARRAY(),?,? FROM t_llm_service s
                    WHERE NOT EXISTS (SELECT 1 FROM t_llm_cost_budget b WHERE b.tenant_id=s.tenant_id AND b.service_id=s.service_id)
                    """, now, now);
            // 删除/改名的模型仍按原业务 ID 保留预算账本，不以名称匹配。
            for (var scope : jdbc.queryForList("SELECT DISTINCT tenant_id,service_id FROM t_cost_budget_settlement WHERE request_started_at>=?", start)) {
                jdbc.update("""
                        INSERT INTO t_llm_cost_budget(id,tenant_id,service_id,warning_channels,create_time,update_time)
                        SELECT ?,?,?,JSON_ARRAY(),?,? WHERE NOT EXISTS
                        (SELECT 1 FROM t_llm_cost_budget WHERE tenant_id=? AND service_id=?)
                        """, SnowflakeIdUtil.nextId(), scope.get("tenant_id"), scope.get("service_id"), now, now,
                        scope.get("tenant_id"), scope.get("service_id"));
            }
            for (var period : time.resolve(instant)) {
                rebuild("API_KEY", "api_key_id", period, now);
                rebuild("LLM", "service_id", period, now);
            }
            jdbc.update("UPDATE t_cost_budget_settlement SET budget_applied=1,applied_at=COALESCE(applied_at,?) WHERE request_started_at>=?", now, start);
            long total = jdbc.queryForObject("SELECT COUNT(*) FROM t_cost_budget_settlement WHERE request_started_at>=?", Long.class, start);
            if (!apply) status.setRollbackOnly();
            return total;
        }));
    }

    private void importUsage(long id, LocalDateTime now) {
        var mapper = BeanPropertyRowMapper.newInstance(TokenUsageDO.class);
        jdbc.query("""
                SELECT u.*,c.id AS snapshot_id,c.cost_status AS snapshot_status,c.estimated_cost_fen AS snapshot_cost,
                       c.currency AS snapshot_currency,o.payload_json AS original_payload
                FROM t_token_usage u JOIN t_token_usage_cost c ON c.usage_id=u.id
                LEFT JOIN t_token_usage_outbox o ON o.event_id COLLATE utf8mb4_unicode_ci=u.event_id COLLATE utf8mb4_unicode_ci
                WHERE u.id=?
                """, (rs, index) -> {
            var usage = mapper.mapRow(rs, index);
            TokenUsageEvent event;
            String payload = rs.getString("original_payload");
            if (payload != null) event = JSON.parseObject(payload, TokenUsageEvent.class);
            else {
                event = new TokenUsageEvent();
                event.setEventId(usage.getEventId()); event.setTenantId(usage.getTenantId()); event.setUserId(usage.getUserId());
                event.setApiKeyId(usage.getApiKeyId()); event.setServiceId(usage.getServiceId()); event.setSource(usage.getSource());
                event.setOperation(usage.getOperation()); event.setRequestId(usage.getRequestId()); event.setModel(usage.getModel());
                event.setStatus(usage.getStatus()); event.setUsageSource(usage.getUsageSource());
                event.setPromptTokens(usage.getPromptTokens()); event.setCompletionTokens(usage.getCompletionTokens());
                event.setTotalTokens(usage.getTotalTokens()); event.setUsageDetailsJson(usage.getUsageDetailsJson());
                event.setRequestStartedAt(usage.getRequestStartedAt().atZone(BudgetPeriodResolver.ZONE).toInstant());
            }
            // 已刷入费用表的原始终态是权威数据；这里不重新解析用量或计算价格。
            String currency = rs.getString("snapshot_currency");
            String costStatus = rs.getString("snapshot_status");
            BigDecimal amount = rs.getBigDecimal("snapshot_cost");
            if (currency != null && !"CNY".equals(currency)) throw new IllegalStateException("unsupported original currency: usage=" + id);
            if (!Objects.equals(event.getTenantId(), usage.getTenantId()) || !Objects.equals(event.getServiceId(), usage.getServiceId())
                    || !Objects.equals(event.getUserId(), usage.getUserId()) || !Objects.equals(event.getOperation(), usage.getOperation())
                    || !Objects.equals(event.getApiKeyId(), usage.getApiKeyId()) || !Objects.equals(event.getSource(), usage.getSource())
                    || !Objects.equals(event.getRequestId(), usage.getRequestId()) || !Objects.equals(event.getEventId(), usage.getEventId())
                    || !time.local(event.getRequestStartedAt()).equals(usage.getRequestStartedAt())) {
                throw new IllegalStateException("usage/Outbox ownership or start conflict: usage=" + id);
            }
            event.setCostResult(new CostResult(CostStatus.valueOf(costStatus), amount));
            store(event, now);
            return id;
        }, id);
    }

    private void store(TokenUsageEvent event, LocalDateTime now) {
        CostBudgetSnapshot.validate(event);
        if (event.getExecutionOutcome() == com.yonagi.verse.service.forward.UpstreamExecutionOutcome.UNKNOWN)
            throw new IllegalStateException("unresolved original execution: event=" + event.getEventId());
        var existing = jdbc.query("SELECT * FROM t_cost_budget_settlement WHERE event_id=? OR (tenant_id=? AND source=? AND request_id=?)",
                BeanPropertyRowMapper.newInstance(CostBudgetSettlementDO.class), event.getEventId(), event.getTenantId(), event.getSource(), event.getRequestId());
        if (!existing.isEmpty()) {
            var fact = existing.getFirst();
            if (existing.size() != 1 || !Objects.equals(fact.getEventId(), event.getEventId())
                    || !Objects.equals(fact.getTenantId(), event.getTenantId()) || !Objects.equals(fact.getUserId(), event.getUserId())
                    || !Objects.equals(fact.getApiKeyId(), event.getApiKeyId()) || !Objects.equals(fact.getServiceId(), event.getServiceId())
                    || !Objects.equals(fact.getSource(), event.getSource()) || !Objects.equals(fact.getRequestId(), event.getRequestId())
                    || !Objects.equals(fact.getOperation(), event.getOperation())
                    || !fact.getRequestStartedAt().equals(time.local(event.getRequestStartedAt()))
                    || !fact.getCostStatus().equals(event.getCostResult().status().name())
                    || !sameAmount(fact.getEstimatedCostFen(), event.getCostResult().estimatedCostFen()) || !"CNY".equals(fact.getCurrency())) {
                throw new IllegalStateException("historical snapshot conflict: event=" + event.getEventId());
            }
            return;
        }
        jdbc.update("""
                INSERT INTO t_cost_budget_settlement(event_id,tenant_id,user_id,api_key_id,service_id,source,operation,
                  request_id,request_started_at,cost_status,estimated_cost_fen,currency,event_payload_json,payload_hash,
                  budget_applied,origin,create_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,'CNY',?,?,0,'HISTORY_IMPORT',?)
                """, event.getEventId(), event.getTenantId(), event.getUserId(), event.getApiKeyId(), event.getServiceId(),
                event.getSource(), event.getOperation(), event.getRequestId(), time.local(event.getRequestStartedAt()),
                event.getCostResult().status().name(), event.getCostResult().estimatedCostFen(), JSON.toJSONString(event),
                CostBudgetSnapshot.fingerprint(event), now);
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private void rebuild(String scope, String column, BudgetPeriodResolver.Period period, LocalDateTime now) {
        // column 来自本类固定调用，覆盖写入可重复执行，避免给已有 Key 累计再次追加历史。
        jdbc.update("DELETE FROM t_cost_budget_period WHERE scope_type=? AND period_type=? AND period_start=?", scope, period.type(), period.start());
        jdbc.update("""
                INSERT INTO t_cost_budget_period(tenant_id,scope_type,scope_id,period_type,period_start,period_end,
                    used_cost_fen,calculated_count,unpriced_count,uncalculable_count,not_chargeable_count,update_time)
                SELECT tenant_id,?,%s,?,?,?,
                    SUM(CASE WHEN cost_status='CALCULATED' THEN estimated_cost_fen ELSE 0 END),
                    SUM(cost_status='CALCULATED'),SUM(cost_status='UNPRICED'),SUM(cost_status='UNCALCULABLE'),
                    SUM(cost_status='NOT_CHARGEABLE'),? FROM t_cost_budget_settlement
                WHERE request_started_at>=? AND request_started_at<? AND %s IS NOT NULL GROUP BY tenant_id,%s
                """.formatted(column, column, column), scope, period.type(), period.start(), period.end(), now, period.start(), period.end());
    }

    private void page(String table, String condition, List<Object> parameters, Consumer<Long> action) {
        long cursor = 0;
        while (true) {
            var args = new java.util.ArrayList<Object>(parameters); args.add(cursor);
            List<Long> ids = jdbc.queryForList("SELECT id FROM " + table + " WHERE " + condition + " AND id>? ORDER BY id LIMIT 500", Long.class, args.toArray());
            if (ids.isEmpty()) return;
            ids.forEach(action); cursor = ids.getLast();
        }
    }

    private void requireZero(String sql, String reason, Object... args) {
        if (jdbc.queryForObject(sql, Long.class, args) != 0) throw new IllegalStateException(reason);
    }

    /** 旧状态仅在升级入口读取；真实故障不能随删除冗余列一起丢失。 */
    private void requireNoFault(String table) {
        String oldFault = hasColumn(table, "cost_data_state") ? " OR cost_data_state='UNAVAILABLE'" : "";
        requireZero("SELECT COUNT(*) FROM " + table + " WHERE cost_data_reason IS NOT NULL" + oldFault,
                table + " has an unresolved runtime fault");
    }

    private boolean hasColumn(String table, String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Long.class, table, column) != 0;
    }

    /** 仅在离线升级入口删除旧版双标志，线上不保留任何双版本判断。DDL 失败可重新执行命令。 */
    public void finishSchemaUpgrade() {
        long oldColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                AND table_name='t_cost_budget_settlement' AND column_name='llm_budget_applied'
                """, Long.class);
        if (oldColumns != 0) jdbc.execute("ALTER TABLE t_cost_budget_settlement DROP INDEX idx_budget_settlement_llm_pending,DROP COLUMN llm_budget_applied,DROP COLUMN llm_applied_at");
        for (String table : List.of("t_api_key", "t_llm_cost_budget")) {
            if (hasColumn(table, "cost_data_state")) jdbc.execute("ALTER TABLE " + table + " DROP COLUMN cost_data_state");
        }
        for (String table : List.of("t_cost_budget_invocation", "t_cost_budget_invocation_model", "t_cost_budget_settlement",
                "t_cost_budget_period", "t_llm_cost_budget", "t_llm_cost_alert_event", "t_llm_cost_alert_delivery")) {
            String collation = jdbc.queryForObject("SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", String.class, table);
            if (!"utf8mb4_unicode_ci".equals(collation)) jdbc.execute("ALTER TABLE " + table + " CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }
}
