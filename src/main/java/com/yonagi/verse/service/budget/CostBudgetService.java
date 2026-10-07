package com.yonagi.verse.service.budget;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.event.LlmAuditEvent;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.CostLimitPatch;
import com.yonagi.verse.dto.resp.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 主库预算真相来源；每个事务只持有短行锁，不等待上游调用。 */
@Slf4j
@Service
public class CostBudgetService {
    private final ApiKeyMapper keys;
    private final CostBudgetInvocationMapper invocations;
    private final CostBudgetSettlementMapper settlements;
    private final CostBudgetPeriodMapper periods;
    private final BudgetPeriodResolver time;
    private final BudgetExecutionRegistry registry;
    private final UsageOutboxStager outbox;
    private final UsageOutboxProperties outboxProperties;
    private final CostBudgetAudit audit;
    private final TransactionTemplate transaction;
    private final Map<String, TokenUsageEvent> pendingSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    @Value("${verse.llm.costing.enabled:true}") private boolean costingEnabled;

    public CostBudgetService(ApiKeyMapper keys, CostBudgetInvocationMapper invocations,
            CostBudgetSettlementMapper settlements, CostBudgetPeriodMapper periods,
            BudgetPeriodResolver time, BudgetExecutionRegistry registry, UsageOutboxStager outbox,
            UsageOutboxProperties outboxProperties, PlatformTransactionManager manager, CostBudgetAudit audit) {
        this.keys = keys; this.invocations = invocations; this.settlements = settlements;
        this.periods = periods; this.time = time; this.registry = registry;
        this.outbox = outbox; this.outboxProperties = outboxProperties;
        this.audit = audit;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ApiKeyDO lockKey(Long tenantId, Long keyId) {
        ApiKeyDO key = keys.lockBudgetKey(tenantId, keyId);
        if (key == null) throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_NOT_EXIST);
        return key;
    }
    public ApiKeyDO lockOwnedKey(Long tenantId, Long keyId, Long userId) {
        ApiKeyDO key = keys.lockOwnedBudgetKey(tenantId, keyId, userId);
        if (key == null) throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_NOT_EXIST);
        return key;
    }

    /** 在管理服务的 Key 锁内合并；字段缺失保持、显式 null 清空。 */
    public void merge(ApiKeyDO key, CostLimitPatch patch) {
        if (patch == null) return;
        CostLimitConfig previous = CostLimitConfig.from(key);
        if (patch.getExpectedVersion() != null && !previous.version().equals(patch.getExpectedVersion())) {
            throw new ClientException(CostBudgetErrorCodeEnum.VERSION_CONFLICT);
        }
        if (patch.getEnabled() != null) key.setCostLimitEnabled(patch.getEnabled());
        if (patch.isDailyPresent()) key.setCostLimitDailyFen(decimal(patch.getDailyLimitFen()));
        if (patch.isWeeklyPresent()) key.setCostLimitWeeklyFen(decimal(patch.getWeeklyLimitFen()));
        if (patch.isMonthlyPresent()) key.setCostLimitMonthlyFen(decimal(patch.getMonthlyLimitFen()));
        if (Boolean.TRUE.equals(key.getCostLimitEnabled())) {
            if (key.getCostLimitDailyFen() == null && key.getCostLimitWeeklyFen() == null
                    && key.getCostLimitMonthlyFen() == null) throw new ClientException(CostBudgetErrorCodeEnum.INVALID_CONFIG);
            if (!costingEnabled || !outboxProperties.isEnabled()
                    || !"READY".equals(key.getCostDataState()) || !complete(key, time.now())) {
                throw new ClientException(CostBudgetErrorCodeEnum.NOT_READY);
            }
        }
        CostLimitConfig next = CostLimitConfig.from(key);
        if (!previous.equals(next)) {
            key.setCostConfigVersion(Long.parseLong(previous.version()) + 1);
            audit.configured(key);
        }
    }

    public void recordRejection(UserContext ctx, String requestId, com.yonagi.verse.common.convention.exception.AbstractException exception) {
        try { audit.rejected(ctx, requestId, exception); }
        catch (RuntimeException e) { log.warn("[cost-budget] 拒绝审计暂存失败: requestId={}", requestId, e); }
    }

    public ApiKeyCostStatusRespDTO status(Long tenantId, Long keyId, Long userId) {
        return transaction.execute(ignored -> {
            ApiKeyDO key = lockOwnedKey(tenantId, keyId, userId);
            return snapshot(key, time.now());
        });
    }

    /** 合并前每个 HTTP 请求均检查；实际执行者另行登记。 */
    public void check(UserContext ctx) {
        if (ctx == null || ctx.getApiKeyId() == null) return;
        try {
            transaction.executeWithoutResult(ignored -> enforce(lockKey(ctx.getCurrentTenantId(), ctx.getApiKeyId())));
        } catch (CostLimitExceededException | CostBudgetUnavailableException e) { throw e;
        } catch (RuntimeException e) {
            log.warn("[cost-budget] 主库检查失败: keyId={}", ctx.getApiKeyId(), e);
            throw new CostBudgetUnavailableException();
        }
    }

    /** 登记唯一逻辑调用，重试和备用模型复用同一个记录。 */
    public void begin(UserContext ctx, String requestId, Instant started) {
        if (ctx.getApiKeyId() == null) return;
        registry.register(requestId);
        try {
            transaction.executeWithoutResult(ignored -> {
                ApiKeyDO key = lockKey(ctx.getCurrentTenantId(), ctx.getApiKeyId());
                enforce(key);
                if (invocations.lockRequest(requestId) != null) throw new CostBudgetUnavailableException();
                CostBudgetInvocationDO row = new CostBudgetInvocationDO();
                row.setId(SnowflakeIdUtil.nextId()); row.setTenantId(key.getTenantId()); row.setApiKeyId(key.getApiKeyId());
                row.setRequestId(requestId); row.setEventId(String.valueOf(SnowflakeIdUtil.nextId()));
                row.setRequestStartedAt(time.local(started)); row.setOwnerId(registry.owner());
                row.setOwnerGeneration(registry.generation()); row.setState("RUNNING");
                row.setUpdateTime(time.local(time.now())); row.setLastHeartbeatAt(row.getUpdateTime());
                row.setLeaseUntil(row.getUpdateTime().plusSeconds(30));
                try { invocations.insert(row); }
                catch (RuntimeException failure) {
                    if (Boolean.TRUE.equals(key.getCostLimitEnabled())) throw failure;
                    // 关闭预算时接管表故障可继续调用，但必须在同一 Key 锁内留下覆盖缺口。
                    // 主库也不可写时事务失败，认证与主库检查同样无法保证请求可用。
                    keys.update(Wrappers.lambdaUpdate(ApiKeyDO.class).eq(ApiKeyDO::getTenantId, key.getTenantId())
                            .eq(ApiKeyDO::getApiKeyId, key.getApiKeyId()).set(ApiKeyDO::getCostDataState, "UNAVAILABLE")
                            .set(ApiKeyDO::getCostDataReason, "INVOCATION_WRITE_FAILED"));
                    log.error("[cost-budget] 关闭预算期间接管失败，已登记数据覆盖缺口: keyId={}, requestId={}", key.getApiKeyId(), requestId, failure);
                }
            });
        } catch (RuntimeException e) {
            registry.remove(requestId);
            if (e instanceof CostLimitExceededException || e instanceof CostBudgetUnavailableException) throw e;
            throw new CostBudgetUnavailableException();
        }
    }

    public void sent(String requestId) { registry.sent(requestId); }
    public void finalizing(String requestId) { registry.finalizing(requestId); }
    public boolean hasUpstream(String requestId) { return registry.executed(requestId); }

    /** 无订阅或在发送前被韧性规则拒绝时，必须证明没有实际上游执行。 */
    public void abandon(UserContext ctx, String requestId) {
        if (ctx.getApiKeyId() == null || !registry.noUpstream(requestId)) return;
        try {
            transaction.executeWithoutResult(ignored -> {
                lockKey(ctx.getCurrentTenantId(), ctx.getApiKeyId());
                CostBudgetInvocationDO row = invocations.lockRequest(requestId);
                if (row != null && "RUNNING".equals(row.getState())) {
                    row.setState("NO_UPSTREAM"); row.setUpdateTime(time.local(time.now())); invocations.updateById(row);
                }
            });
        } finally { registry.remove(requestId); }
    }

    private void enforce(ApiKeyDO key) {
        if (!Boolean.TRUE.equals(key.getCostLimitEnabled())) return;
        ApiKeyCostStatusRespDTO status = snapshot(key, time.now());
        if ("UNKNOWN".equals(status.budgetState())) throw new CostBudgetUnavailableException();
        if ("LIMITED".equals(status.budgetState())) throw new CostLimitExceededException(status);
    }

    private boolean complete(ApiKeyDO key, Instant now) {
        for (CostBudgetInvocationDO row : invocations.unfinished(key.getTenantId(), key.getApiKeyId(), time.earliest(now))) {
            if (!"RUNNING".equals(row.getState()) || !registry.confirm(row)) return false;
        }
        return true;
    }

    private ApiKeyCostStatusRespDTO snapshot(ApiKeyDO key, Instant now) {
        boolean enabled = Boolean.TRUE.equals(key.getCostLimitEnabled());
        String availability = key.getCostDataState() == null ? "INITIALIZING" : key.getCostDataState();
        if ("READY".equals(availability) && (!costingEnabled || !outboxProperties.isEnabled() || !complete(key, now))) {
            availability = "UNAVAILABLE";
        }
        boolean ready = "READY".equals(availability);
        List<ApiKeyCostStatusRespDTO.PeriodStatus> details = new ArrayList<>();
        List<ApiKeyCostStatusRespDTO.Hit> hits = new ArrayList<>();
        for (BudgetPeriodResolver.Period period : time.resolve(now)) {
            BigDecimal limit = limit(key, period.type());
            CostBudgetPeriodDO row = ready ? loadPeriod(key, period) : null;
            Boolean exceeded = row == null ? null : limit != null && row.getUsedCostFen().compareTo(limit) >= 0;
            boolean effective = enabled && Boolean.TRUE.equals(exceeded);
            if (effective) hits.add(new ApiKeyCostStatusRespDTO.Hit("API_KEY", period.type(), time.iso(period.end())));
            details.add(new ApiKeyCostStatusRespDTO.PeriodStatus(period.type(), time.iso(period.start()), time.iso(period.end()),
                    amount(limit), row == null ? null : amount(row.getUsedCostFen()),
                    row == null || limit == null ? null : amount(limit.subtract(row.getUsedCostFen()).max(BigDecimal.ZERO)),
                    exceeded, effective, row == null ? null : String.valueOf(row.getCalculatedCount()),
                    row == null ? null : String.valueOf(row.getUnpricedCount()),
                    row == null ? null : String.valueOf(row.getUncalculableCount()),
                    row == null ? null : String.valueOf(row.getNotChargeableCount())));
        }
        // 按真实结束时间选择主原因；同刻月、周、日稳定排序。
        hits.sort(Comparator.comparing(ApiKeyCostStatusRespDTO.Hit::periodEnd).reversed()
                .thenComparingInt(hit -> switch (hit.period()) { case "MONTH" -> 0; case "WEEK" -> 1; default -> 2; }));
        String state = !enabled ? "DISABLED" : !ready ? "UNKNOWN" : hits.isEmpty() ? "AVAILABLE" : "LIMITED";
        return new ApiKeyCostStatusRespDTO(String.valueOf(key.getApiKeyId()), CostLimitConfig.from(key), "CNY", "Asia/Shanghai",
                time.iso(time.local(now)), availability, state, hits.isEmpty() ? null : hits.getFirst().periodEnd(), details, hits);
    }

    /** READY 的缺行仍从账本覆盖重建，不能把已有账本当成零。 */
    private CostBudgetPeriodDO loadPeriod(ApiKeyDO key, BudgetPeriodResolver.Period period) {
        CostBudgetPeriodDO row = periods.lockPeriod(key.getTenantId(), key.getApiKeyId(), period.type(), period.start());
        if (row != null) return row;
        row = emptyPeriod(key, period);
        for (CostBudgetSettlementDO fact : settlements.history(key.getTenantId(), key.getApiKeyId(), period.start())) {
            if (Boolean.TRUE.equals(fact.getBudgetApplied()) && fact.getRequestStartedAt().isBefore(period.end())) add(row, fact);
        }
        periods.insert(row);
        return row;
    }
    private CostBudgetPeriodDO emptyPeriod(ApiKeyDO key, BudgetPeriodResolver.Period period) {
        CostBudgetPeriodDO row = new CostBudgetPeriodDO(); row.setId(SnowflakeIdUtil.nextId());
        row.setTenantId(key.getTenantId()); row.setScopeId(key.getApiKeyId()); row.setScopeType("API_KEY");
        row.setPeriodType(period.type()); row.setPeriodStart(period.start()); row.setPeriodEnd(period.end());
        row.setUsedCostFen(BigDecimal.ZERO); row.setCalculatedCount(0L); row.setUnpricedCount(0L);
        row.setUncalculableCount(0L); row.setNotChargeableCount(0L); row.setRebuildVersion(0L);
        row.setUpdateTime(time.local(time.now())); return row;
    }

    /** 终态结算失败不改写成功的上游响应；持久化栅栏使后续已开启请求 503。 */
    public void settle(TokenUsageEvent event) {
        registry.finalizing(event.getRequestId());
        pendingSnapshots.putIfAbsent(event.getRequestId(), event);
        try {
            String eventId = transaction.execute(ignored -> stage(event, "LIVE"));
            apply(eventId);
            registry.remove(event.getRequestId());
            pendingSnapshots.remove(event.getRequestId());
        } catch (RuntimeException e) {
            log.error("[cost-budget] 终态待恢复: requestId={}, keyId={}", event.getRequestId(), event.getApiKeyId(), e);
            markUnknown(event.getTenantId(), event.getApiKeyId(), event.getRequestId(),
                    e.getMessage() != null && e.getMessage().contains("conflict") ? "SNAPSHOT_CONFLICT" : "SETTLEMENT_FAILED");
        }
    }

    /** 暂存失败但进程仍存活时重放原始终态；不重新生成费用。 */
    public void retryLocalSnapshots() { List.copyOf(pendingSnapshots.values()).forEach(this::settle); }

    private String stage(TokenUsageEvent event, String origin) {
        ApiKeyDO key = lockKey(event.getTenantId(), event.getApiKeyId());
        CostBudgetInvocationDO invocation = invocations.lockRequest(event.getRequestId());
        if (invocation != null) { event.setEventId(invocation.getEventId()); event.setKey(invocation.getEventId()); }
        else if ("LIVE".equals(origin)) {
            event.setEventId(DigestUtil.sha256Hex("API_KEY:" + event.getTenantId() + ":" + event.getRequestId()));
            event.setKey(event.getEventId());
        }
        validate(event);
        String payload = JSON.toJSONString(event);
        String hash = fingerprint(event);
        CostBudgetSettlementDO existing = settlements.lockEvent(event.getEventId());
        if (existing != null) {
            if (!hash.equals(existing.getPayloadHash())) throw new IllegalStateException("settlement snapshot conflict");
            return existing.getEventId();
        }
        CostBudgetSettlementDO row = new CostBudgetSettlementDO(); row.setId(SnowflakeIdUtil.nextId());
        row.setEventId(event.getEventId()); row.setTenantId(key.getTenantId()); row.setUserId(event.getUserId());
        row.setApiKeyId(key.getApiKeyId()); row.setServiceId(event.getServiceId()); row.setSource(event.getSource());
        row.setOperation(event.getOperation()); row.setRequestId(event.getRequestId());
        row.setRequestStartedAt(time.local(event.getRequestStartedAt())); row.setCostStatus(event.getCostResult().status().name());
        row.setEstimatedCostFen(event.getCostResult().estimatedCostFen()); row.setCurrency("CNY");
        row.setEventPayloadJson(payload); row.setPayloadHash(hash); row.setBudgetApplied(false); row.setOrigin(origin);
        row.setCreateTime(time.local(time.now())); settlements.insert(row);
        if (invocation != null) {
            invocation.setState("FINALIZING"); invocation.setUpdateTime(row.getCreateTime()); invocations.updateById(invocation);
        }
        return row.getEventId();
    }

    public void apply(String eventId) {
        CostBudgetSettlementDO lookup = settlements.selectOne(Wrappers.lambdaQuery(CostBudgetSettlementDO.class)
                .eq(CostBudgetSettlementDO::getEventId, eventId));
        if (lookup == null) return;
        transaction.executeWithoutResult(ignored -> {
            ApiKeyDO key = lockKey(lookup.getTenantId(), lookup.getApiKeyId());
            CostBudgetInvocationDO invocation = invocations.lockRequest(lookup.getRequestId());
            CostBudgetSettlementDO row = settlements.lockEvent(eventId);
            if (Boolean.TRUE.equals(row.getBudgetApplied())) return;
            Instant started = row.getRequestStartedAt().atZone(BudgetPeriodResolver.ZONE).toInstant();
            for (BudgetPeriodResolver.Period period : time.resolve(started)) {
                CostBudgetPeriodDO cumulative = loadPeriod(key, period);
                add(cumulative, row); cumulative.setUpdateTime(time.local(time.now())); periods.updateById(cumulative);
            }
            TokenUsageEvent event = JSON.parseObject(row.getEventPayloadJson(), TokenUsageEvent.class);
            if ("LIVE".equals(row.getOrigin())) outbox.stage(event, row.getEventPayloadJson(), time.local(time.now()));
            row.setBudgetApplied(true); row.setAppliedAt(time.local(time.now())); settlements.updateById(row);
            if (invocation != null) {
                // 已持久化未知费用不代表上游未收费；保留预算栅栏，禁止用零金额放行。
                boolean unknownExecution = event.getExecutionOutcome() == UpstreamExecutionOutcome.UNKNOWN
                        || (CostStatus.UNCALCULABLE.name().equals(row.getCostStatus())
                        && !LlmAuditEvent.STATUS_SUCCESS.equals(event.getStatus()));
                invocation.setState(unknownExecution ? "UNKNOWN" : "SETTLED");
                if (unknownExecution) invocation.setLastErrorCode("UPSTREAM_RESULT_UNKNOWN");
                invocation.setUpdateTime(row.getAppliedAt()); invocations.updateById(invocation);
            }
        });
    }

    private void add(CostBudgetPeriodDO row, CostBudgetSettlementDO fact) {
        switch (CostStatus.valueOf(fact.getCostStatus())) {
            case CALCULATED -> {
                BigDecimal used = row.getUsedCostFen().add(fact.getEstimatedCostFen());
                if (used.precision() - used.scale() > 47 || used.scale() > 18) throw new IllegalStateException("budget overflow");
                row.setUsedCostFen(used); row.setCalculatedCount(Math.addExact(row.getCalculatedCount(), 1));
            }
            case UNPRICED -> row.setUnpricedCount(Math.addExact(row.getUnpricedCount(), 1));
            case UNCALCULABLE -> row.setUncalculableCount(Math.addExact(row.getUncalculableCount(), 1));
            case NOT_CHARGEABLE -> row.setNotChargeableCount(Math.addExact(row.getNotChargeableCount(), 1));
        }
    }
    public void markUnknown(Long tenantId, Long keyId, String requestId, String reason) {
        registry.finalizing(requestId);
        try {
            transaction.executeWithoutResult(ignored -> {
                ApiKeyDO key = lockKey(tenantId, keyId);
                CostBudgetInvocationDO invocation = invocations.lockRequest(requestId);
                if (invocation != null && !"SETTLED".equals(invocation.getState()) && !"NO_UPSTREAM".equals(invocation.getState())) {
                    invocation.setState("UNKNOWN"); invocation.setLastErrorCode(reason);
                    invocation.setUpdateTime(time.local(time.now())); invocations.updateById(invocation);
                }
                if ("SNAPSHOT_CONFLICT".equals(reason)) {
                    key.setCostDataState("UNAVAILABLE"); key.setCostDataReason(reason); keys.updateById(key);
                }
            });
        } catch (RuntimeException e) { log.error("[cost-budget] 异常栅栏未写入，执行确认已停止: requestId={}", requestId, e); }
    }

    /** 覆盖重建，不把扫描结果再次追加；有未完成终态时保持不可用。 */
    public void rebuild(Long tenantId, Long keyId) {
        transaction.executeWithoutResult(ignored -> {
            ApiKeyDO key = lockKey(tenantId, keyId);
            Instant now = time.now();
            var facts = settlements.history(tenantId, keyId, time.earliest(now));
            if (!complete(key, now) || facts.stream().anyMatch(row -> !Boolean.TRUE.equals(row.getBudgetApplied()))) {
                throw new IllegalStateException("unfinished cost snapshot");
            }
            for (var period : time.resolve(now)) {
                CostBudgetPeriodDO current = periods.lockPeriod(tenantId, keyId, period.type(), period.start());
                CostBudgetPeriodDO rebuilt = emptyPeriod(key, period);
                for (var fact : facts) {
                    if (!fact.getRequestStartedAt().isBefore(period.start()) && fact.getRequestStartedAt().isBefore(period.end())) {
                        add(rebuilt, fact);
                    }
                }
                if (current == null) periods.insert(rebuilt);
                else {
                    rebuilt.setId(current.getId()); rebuilt.setRebuildVersion(current.getRebuildVersion() + 1);
                    periods.updateById(rebuilt);
                }
            }
            keys.update(Wrappers.lambdaUpdate(ApiKeyDO.class).eq(ApiKeyDO::getTenantId, tenantId)
                    .eq(ApiKeyDO::getApiKeyId, keyId).set(ApiKeyDO::getCostDataState, "READY")
                    .set(ApiKeyDO::getCostDataReason, null));
        });
    }

    public void recoverInvocation(CostBudgetInvocationDO lookup) {
        if (registry.owner().equals(lookup.getOwnerId()) && "RUNNING".equals(lookup.getState())
                && lookup.getRequestStartedAt().isBefore(time.local(time.now()).minusSeconds(30))) {
            abandon(new UserContext().setCurrentTenantId(lookup.getTenantId()).setApiKeyId(lookup.getApiKeyId()), lookup.getRequestId());
        }
        transaction.executeWithoutResult(ignored -> {
            lockKey(lookup.getTenantId(), lookup.getApiKeyId());
            CostBudgetInvocationDO row = invocations.lockRequest(lookup.getRequestId());
            if (row == null || "SETTLED".equals(row.getState()) || "NO_UPSTREAM".equals(row.getState())
                    || "FINALIZING".equals(row.getState())) return;
            boolean active = registry.confirm(row);
            row.setState(active ? "RUNNING" : "UNKNOWN");
            row.setUpdateTime(time.local(time.now()));
            if (active) { row.setLastHeartbeatAt(row.getUpdateTime()); row.setLeaseUntil(row.getUpdateTime().plusSeconds(30)); }
            else row.setLastErrorCode("OWNER_UNCONFIRMED");
            invocations.updateById(row);
        });
    }

    public static String fingerprint(TokenUsageEvent event) {
        JSONObject tree = JSON.parseObject(JSON.toJSONString(event));
        tree.remove("occurredAt"); tree.remove("key");
        return DigestUtil.sha256Hex(JSON.toJSONString(sorted(tree)));
    }
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put(key.toString(), sorted(item))); return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(CostBudgetService::sorted).toList();
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        return value;
    }
    private void validate(TokenUsageEvent event) {
        if (event.getRequestStartedAt() == null || event.getRequestId() == null || event.getCostResult() == null
                || event.getCostResult().status() == null || !"API_KEY".equals(event.getSource())) {
            throw new IllegalArgumentException("invalid cost snapshot");
        }
        BigDecimal amount = event.getCostResult().estimatedCostFen();
        if (event.getCostResult().status() == CostStatus.CALCULATED && (amount == null || amount.signum() < 0
                || amount.scale() > 18 || amount.precision() - amount.scale() > 20)) {
            throw new IllegalArgumentException("invalid cost amount");
        }
        if (event.getPricingSnapshot() != null && event.getPricingSnapshot().currency() != null
                && !"CNY".equals(event.getPricingSnapshot().currency())) {
            throw new IllegalArgumentException("unsupported currency");
        }
    }
    private static BigDecimal limit(ApiKeyDO key, String type) {
        return switch (type) { case "DAY" -> key.getCostLimitDailyFen(); case "WEEK" -> key.getCostLimitWeeklyFen();
            default -> key.getCostLimitMonthlyFen(); };
    }
    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
    private static String amount(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
