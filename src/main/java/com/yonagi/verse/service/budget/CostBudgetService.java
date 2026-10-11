package com.yonagi.verse.service.budget;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.event.TokenUsageEvent;
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
    private final LlmCostBudgetLedger llmBudgets;
    private io.micrometer.core.instrument.MeterRegistry metrics;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMetrics(io.micrometer.core.instrument.MeterRegistry registry) { this.metrics = registry; }
    private final ApiKeyMapper keys;
    private final CostBudgetInvocationMapper invocations;
    private final CostBudgetSettlementMapper settlements;
    private final CostBudgetPeriodMapper periods;
    private final BudgetPeriodResolver time;
    private final BudgetExecutionRegistry registry;
    private final UsageOutboxStager outbox;
    private final UsageOutboxProperties outboxProperties;
    private final TransactionTemplate transaction;
    private final Set<String> untrackedRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, TokenUsageEvent> pendingSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    @Value("${verse.llm.costing.enabled:true}") private boolean costingEnabled;

    public CostBudgetService(ApiKeyMapper keys, CostBudgetInvocationMapper invocations,
            CostBudgetSettlementMapper settlements, CostBudgetPeriodMapper periods,
            BudgetPeriodResolver time, BudgetExecutionRegistry registry, UsageOutboxStager outbox,
            UsageOutboxProperties outboxProperties, PlatformTransactionManager manager, LlmCostBudgetLedger llmBudgets) {
        this.llmBudgets = llmBudgets;
        this.keys = keys; this.invocations = invocations; this.settlements = settlements;
        this.periods = periods; this.time = time; this.registry = registry;
        this.outbox = outbox; this.outboxProperties = outboxProperties;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private <T> T shortTransaction(java.util.function.Function<org.springframework.transaction.TransactionStatus, T> action) {
        for (int attempt = 0; ; attempt++) {
            try { return transaction.execute(action::apply); }
            catch (LlmCostBudgetLedger.ParticipantSetChangedException | org.springframework.dao.PessimisticLockingFailureException conflict) {
                if (attempt >= 2) throw new CostBudgetUnavailableException();
            }
        }
    }
    private void shortTransactionVoid(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
        shortTransaction(status -> { action.accept(status); return null; });
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
                    || key.getCostDataReason() != null || !complete(key, time.now())) {
                throw new ClientException(CostBudgetErrorCodeEnum.NOT_READY);
            }
        }
        CostLimitConfig next = CostLimitConfig.from(key);
        if (!previous.equals(next)) {
            key.setCostConfigVersion(Long.parseLong(previous.version()) + 1);
        }
    }

    public ApiKeyCostStatusRespDTO status(Long tenantId, Long keyId, Long userId) {
        return shortTransaction(ignored -> {
            ApiKeyDO key = lockOwnedKey(tenantId, keyId, userId);
            return snapshot(key, time.now());
        });
    }

    /** 完整收集 Key 和实际模型命中；任一启用作用域未知时优先 503。 */
    public void check(UserContext ctx, Long serviceId) {
        try {
            shortTransactionVoid(ignored -> {
                ApiKeyDO key = ctx.getApiKeyId() == null ? null : lockKey(ctx.getCurrentTenantId(), ctx.getApiKeyId());
                var model = llmBudgets.lock(ctx.getCurrentTenantId(), serviceId);
                joint(key, model).enforce();
            });
        } catch (CostLimitExceededException | CostBudgetUnavailableException e) { throw e;
        } catch (RuntimeException e) { throw new CostBudgetUnavailableException(); }
    }
    private CostBudgetDecision joint(ApiKeyDO key, LlmCostBudgetDO model) {
        long started = System.nanoTime();
        try {
            List<ApiKeyCostStatusRespDTO.Hit> hits = new ArrayList<>(); boolean unknown = false;
            if (key != null && Boolean.TRUE.equals(key.getCostLimitEnabled())) {
                var state = snapshot(key, time.now()); hits.addAll(state.limits()); unknown = "UNKNOWN".equals(state.budgetState());
            }
            var decision = llmBudgets.decision(model); hits.addAll(decision.limits()); unknown |= decision.unavailable();
            if (unknown && !hits.isEmpty()) log.warn("[cost-budget] 联合检查未知，保留已知命中: keyId={}, serviceId={}, knownLimits={}",
                    key == null ? null : key.getApiKeyId(), model.getServiceId(), hits);
            if (metrics != null) {
                if (unknown) metrics.counter("verse.cost.budget.unknown", "reason", "LEDGER_INCOMPLETE").increment();
                else hits.forEach(hit -> metrics.counter("verse.cost.budget.rejections", "scope", hit.scope(), "period", hit.period()).increment());
            }
            return new CostBudgetDecision(hits, unknown);
        } finally {
            if (metrics != null) metrics.timer("verse.cost.budget.check.latency").record(System.nanoTime()-started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
    public void begin(UserContext ctx, Long serviceId, String requestId, Instant started) {
        registry.register(requestId);
        try {
            shortTransactionVoid(ignored -> {
                ApiKeyDO key = ctx.getApiKeyId() == null ? null : lockKey(ctx.getCurrentTenantId(), ctx.getApiKeyId());
                var model = llmBudgets.lock(ctx.getCurrentTenantId(), serviceId); joint(key, model).enforce();
                var row = new CostBudgetInvocationDO(); row.setId(SnowflakeIdUtil.nextId()); row.setTenantId(ctx.getCurrentTenantId());
                row.setApiKeyId(ctx.getApiKeyId()); row.setSource(ctx.getApiKeyId() == null ? "PLAYGROUND" : "API_KEY");
                row.setRequestId(requestId); row.setEventId(String.valueOf(SnowflakeIdUtil.nextId())); row.setRequestStartedAt(time.local(started));
                row.setOwnerId(registry.owner()); row.setOwnerGeneration(registry.generation()); row.setState("RUNNING");
                row.setUpdateTime(time.local(time.now())); row.setLastHeartbeatAt(row.getUpdateTime()); row.setLeaseUntil(row.getUpdateTime().plusSeconds(30));
                try {
                    if (invocations.lockRequest(requestId) != null) throw new CostBudgetUnavailableException();
                    invocations.insert(row); llmBudgets.register(ctx.getCurrentTenantId(), requestId, serviceId); }
                catch (RuntimeException storageFailure) {
                    if (storageFailure instanceof CostBudgetUnavailableException) throw storageFailure;
                    if (Boolean.TRUE.equals(model.getCostLimitEnabled()) || key != null && Boolean.TRUE.equals(key.getCostLimitEnabled())) throw storageFailure;
                    llmBudgets.unavailable(List.of(model), "INVOCATION_WRITE_FAILED");
                    if (key != null) keys.update(Wrappers.lambdaUpdate(ApiKeyDO.class).eq(ApiKeyDO::getId, key.getId())
                            .set(ApiKeyDO::getCostDataReason, "INVOCATION_WRITE_FAILED"));
                    untrackedRequests.add(requestId);
                }
            });
        } catch (RuntimeException e) {
            registry.remove(requestId); untrackedRequests.remove(requestId);
            if (e instanceof CostLimitExceededException exceeded) throw exceeded.requestId(requestId);
            if (e instanceof CostBudgetUnavailableException) throw e;
            throw new CostBudgetUnavailableException();
        }
    }
    /** 每次真正发送（含重试/备用）前，先锁全部参与模型再登记目标。 */
    public void permit(UserContext ctx, Long serviceId, String requestId) {
        authorize(ctx, serviceId, requestId);
        sent(requestId);
    }
    /** SSE 返回响应前取得单次许可；订阅只消费许可，未订阅仍可证明没有上游执行。 */
    public void prepareStream(UserContext ctx, Long serviceId, String requestId) {
        authorize(ctx, serviceId, requestId);
    }
    private void authorize(UserContext ctx, Long serviceId, String requestId) {
        if (untrackedRequests.contains(requestId)) {
            shortTransactionVoid(ignored -> {
                var key = ctx.getApiKeyId() == null ? null : lockKey(ctx.getCurrentTenantId(), ctx.getApiKeyId());
                var model = llmBudgets.lock(ctx.getCurrentTenantId(), serviceId);
                joint(key, model).enforce();
                llmBudgets.unavailable(List.of(model), "INVOCATION_WRITE_FAILED");
            });
            return;
        }
        try { shortTransactionVoid(ignored -> {
            ApiKeyDO key = ctx.getApiKeyId() == null ? null : lockKey(ctx.getCurrentTenantId(), ctx.getApiKeyId());
            var models = llmBudgets.lockForRequest(ctx.getCurrentTenantId(), requestId, serviceId);
            var invocation = invocations.lockRequest(requestId);
            if (invocation == null || !"RUNNING".equals(invocation.getState()) || !registry.owner().equals(invocation.getOwnerId())
                    || !registry.generation().equals(invocation.getOwnerGeneration()))
                throw new CostBudgetUnavailableException();
            llmBudgets.verifyParticipants(requestId, models);
            var target = models.stream().filter(row -> serviceId.equals(row.getServiceId())).findFirst().orElseThrow();
            joint(key, target).enforce(); llmBudgets.register(ctx.getCurrentTenantId(), requestId, serviceId);
        }); } catch (CostLimitExceededException exceeded) { throw exceeded.requestId(requestId); }
        catch (CostBudgetUnavailableException unavailable) { throw unavailable; }
        catch (RuntimeException failure) { throw new CostBudgetUnavailableException(); }
    }

    public void sent(String requestId) { registry.sent(requestId); }
    public void finalizing(String requestId) { registry.finalizing(requestId); }
    public boolean hasUpstream(String requestId) { return registry.executed(requestId); }

    /** 无订阅或在发送前被韧性规则拒绝时，必须证明没有实际上游执行。 */
    public void abandon(UserContext ctx, String requestId) {
        if (!registry.noUpstream(requestId)) return;
        try {
            shortTransactionVoid(ignored -> {
                if (ctx.getApiKeyId() != null) lockKey(ctx.getCurrentTenantId(), ctx.getApiKeyId());
                var models = llmBudgets.lockForRequest(ctx.getCurrentTenantId(), requestId, null);
                CostBudgetInvocationDO row = invocations.lockRequest(requestId);
                llmBudgets.verifyParticipants(requestId, models);
                if (row != null && "RUNNING".equals(row.getState())) {
                    row.setState("NO_UPSTREAM"); row.setUpdateTime(time.local(time.now())); invocations.updateById(row);
                }
            });
        } finally { registry.remove(requestId); untrackedRequests.remove(requestId); }
    }

    private boolean complete(ApiKeyDO key, Instant now) {
        var unfinished = invocations.unfinished(key.getTenantId(), key.getApiKeyId(), time.earliest(now));
        if (unfinished.size() > 1000) return false;
        if (unfinished.stream().anyMatch(row -> !"RUNNING".equals(row.getState()))) return false;
        return registry.confirmed(unfinished).size() == unfinished.size();
    }

    private ApiKeyCostStatusRespDTO snapshot(ApiKeyDO key, Instant now) {
        boolean enabled = Boolean.TRUE.equals(key.getCostLimitEnabled());
        String availability = key.getCostDataReason() == null ? "READY" : "UNAVAILABLE";
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
        long started = System.nanoTime();
        registry.finalizing(event.getRequestId());
        pendingSnapshots.putIfAbsent(event.getRequestId(), event);
        try {
            String eventId = shortTransaction(ignored -> stage(event));
            apply(eventId);
            registry.remove(event.getRequestId());
            pendingSnapshots.remove(event.getRequestId()); untrackedRequests.remove(event.getRequestId());
        } catch (RuntimeException e) {
            log.error("[cost-budget] 终态待恢复: requestId={}, keyId={}", event.getRequestId(), event.getApiKeyId(), e);
            markUnknown(event.getTenantId(), event.getApiKeyId(), event.getRequestId(),
                    e instanceof CostBudgetSnapshot.ConflictException ? "SNAPSHOT_CONFLICT" : "SETTLEMENT_FAILED");
        } finally {
            if (metrics != null) metrics.timer("verse.cost.budget.settlement.latency").record(System.nanoTime()-started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /** 暂存失败但进程仍存活时重放原始终态；不重新生成费用。 */
    public void retryLocalSnapshots() { List.copyOf(pendingSnapshots.values()).forEach(this::settle); }

    private String stage(TokenUsageEvent event) {
        ApiKeyDO key = event.getApiKeyId() == null ? null : lockKey(event.getTenantId(), event.getApiKeyId());
        var models = llmBudgets.lockForRequest(event.getTenantId(), event.getRequestId(), event.getServiceId());
        CostBudgetInvocationDO invocation = invocations.lockRequest(event.getRequestId());
        llmBudgets.verifyParticipants(event.getRequestId(), models);
        if (invocation != null) { event.setEventId(invocation.getEventId()); event.setKey(invocation.getEventId()); }
        else {
            event.setEventId(DigestUtil.sha256Hex(event.getSource() + ":" + event.getTenantId() + ":" + event.getRequestId()));
            event.setKey(event.getEventId());
        }
        CostBudgetSnapshot.validate(event);
        String payload = JSON.toJSONString(event);
        String hash = CostBudgetSnapshot.fingerprint(event);
        CostBudgetSettlementDO existing = settlements.lockEvent(event.getEventId());
        if (existing != null) {
            if (!hash.equals(existing.getPayloadHash())) throw new CostBudgetSnapshot.ConflictException();
            return existing.getEventId();
        }
        CostBudgetSettlementDO row = new CostBudgetSettlementDO(); row.setId(SnowflakeIdUtil.nextId());
        row.setEventId(event.getEventId()); row.setTenantId(event.getTenantId()); row.setUserId(event.getUserId());
        row.setApiKeyId(event.getApiKeyId()); row.setServiceId(event.getServiceId()); row.setSource(event.getSource());
        row.setOperation(event.getOperation()); row.setRequestId(event.getRequestId());
        row.setRequestStartedAt(time.local(event.getRequestStartedAt())); row.setCostStatus(event.getCostResult().status().name());
        row.setEstimatedCostFen(event.getCostResult().estimatedCostFen()); row.setCurrency("CNY");
        row.setEventPayloadJson(payload); row.setPayloadHash(hash); row.setBudgetApplied(false); row.setOrigin("LIVE");
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
        shortTransactionVoid(ignored -> {
            ApiKeyDO key = lookup.getApiKeyId() == null ? null : lockKey(lookup.getTenantId(), lookup.getApiKeyId());
            var models = llmBudgets.lockForRequest(lookup.getTenantId(), lookup.getRequestId(), lookup.getServiceId());
            CostBudgetInvocationDO invocation = invocations.lockRequest(lookup.getRequestId());
            llmBudgets.verifyParticipants(lookup.getRequestId(), models);
            CostBudgetSettlementDO row = settlements.lockEvent(eventId);
            if (Boolean.TRUE.equals(row.getBudgetApplied())) return;
            Instant started = row.getRequestStartedAt().atZone(BudgetPeriodResolver.ZONE).toInstant();
            if (key != null) for (BudgetPeriodResolver.Period period : time.resolve(started)) {
                CostBudgetPeriodDO cumulative = loadPeriod(key, period);
                add(cumulative, row); cumulative.setUpdateTime(time.local(time.now())); periods.updateById(cumulative);
            }
            TokenUsageEvent event = JSON.parseObject(row.getEventPayloadJson(), TokenUsageEvent.class);
            if (invocation != null) {
                // 已持久化未知费用不代表上游未收费；保留预算栅栏，禁止用零金额放行。
                boolean unknownExecution = event.getExecutionOutcome() == UpstreamExecutionOutcome.UNKNOWN;
                invocation.setState(unknownExecution ? "UNKNOWN" : "SETTLED");
                if (unknownExecution) invocation.setLastErrorCode("UPSTREAM_RESULT_UNKNOWN");
                invocation.setUpdateTime(time.local(time.now())); invocations.updateById(invocation);
            }
            llmBudgets.apply(models.stream().filter(model -> row.getServiceId().equals(model.getServiceId())).findFirst().orElseThrow(), row);
            if ("LIVE".equals(row.getOrigin())) outbox.stage(event, row.getEventPayloadJson(), time.local(time.now()));
            row.setBudgetApplied(true); row.setAppliedAt(time.local(time.now())); settlements.updateById(row);

        });
    }

    static void add(CostBudgetPeriodDO row, CostBudgetSettlementDO fact) {
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
            shortTransactionVoid(ignored -> {
                ApiKeyDO key = keyId == null ? null : lockKey(tenantId, keyId);
                var models = llmBudgets.lockForRequest(tenantId, requestId, null);
                CostBudgetInvocationDO invocation = invocations.lockRequest(requestId);
                llmBudgets.verifyParticipants(requestId, models);
                if (invocation != null && !"SETTLED".equals(invocation.getState()) && !"NO_UPSTREAM".equals(invocation.getState())) {
                    invocation.setState("UNKNOWN"); invocation.setLastErrorCode(reason);
                    invocation.setUpdateTime(time.local(time.now())); invocations.updateById(invocation);
                }
                if ("SNAPSHOT_CONFLICT".equals(reason)) llmBudgets.unavailable(models, reason);
                if ("SNAPSHOT_CONFLICT".equals(reason) && key != null) {
                    key.setCostDataReason(reason); keys.updateById(key);
                }
            });
        } catch (RuntimeException e) { log.error("[cost-budget] 异常栅栏未写入，执行确认已停止: requestId={}", requestId, e); }
    }

    /** 覆盖重建，不把扫描结果再次追加；有未完成终态时保持不可用。 */
    public void rebuild(Long tenantId, Long keyId) {
        shortTransactionVoid(ignored -> {
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
                    .eq(ApiKeyDO::getApiKeyId, keyId)
                    .set(ApiKeyDO::getCostDataReason, null));
        });
    }

    public void recoverInvocation(CostBudgetInvocationDO lookup) {
        if (registry.owner().equals(lookup.getOwnerId()) && "RUNNING".equals(lookup.getState())
                && lookup.getRequestStartedAt().isBefore(time.local(time.now()).minusSeconds(30))) {
            abandon(new UserContext().setCurrentTenantId(lookup.getTenantId()).setApiKeyId(lookup.getApiKeyId()), lookup.getRequestId());
        }
        shortTransactionVoid(ignored -> {
            if (lookup.getApiKeyId() != null) lockKey(lookup.getTenantId(), lookup.getApiKeyId());
            var models = llmBudgets.lockForRequest(lookup.getTenantId(), lookup.getRequestId(), null);
            CostBudgetInvocationDO row = invocations.lockRequest(lookup.getRequestId());
            llmBudgets.verifyParticipants(lookup.getRequestId(), models);
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

    private static BigDecimal limit(ApiKeyDO key, String type) {
        return switch (type) { case "DAY" -> key.getCostLimitDailyFen(); case "WEEK" -> key.getCostLimitWeeklyFen();
            default -> key.getCostLimitMonthlyFen(); };
    }
    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
    private static String amount(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
