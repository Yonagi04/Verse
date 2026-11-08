package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 模型费用账本适配；调用方必须在短事务内先持有配置行锁。 */
@Service @RequiredArgsConstructor
public class LlmCostBudgetLedger {
    private final LlmCostBudgetMapper budgets;
    private final CostBudgetSettlementMapper settlements;
    private final CostBudgetPeriodMapper periods;
    private final BudgetExecutionRegistry registry;
    private final BudgetPeriodResolver time;
    private final UsageOutboxProperties outboxProperties;
    private final LlmCostAlertCaptureService alerts;
    @Value("${verse.llm.costing.enabled:true}") private boolean costingEnabled;
    private CostReconciliationBudgetRead reconciledBudgets;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReconciledBudgets(CostReconciliationBudgetRead read) {
        this.reconciledBudgets = read;
    }

    public LlmCostBudgetDO lock(Long tenantId, Long serviceId) {
        LlmCostBudgetDO row = budgets.lock(tenantId, serviceId);
        // 配置必须由创建事务或离线迁移建立；缺行属于部署/数据错误，不能自动当成零费用。
        if (row == null) throw new CostBudgetUnavailableException();
        return row;
    }
    public List<Long> participants(String requestId) { return budgets.participants(requestId); }
    public List<LlmCostBudgetDO> lockForRequest(Long tenantId, String requestId, Long target) {
        TreeSet<Long> ids = new TreeSet<>(participants(requestId));
        if (target != null) ids.add(target);
        return ids.stream().map(id -> lock(tenantId, id)).toList();
    }
    /** invocation 锁后进行当前读；参与集合变化必须回滚并从固定锁序重试。 */
    public void verifyParticipants(String requestId, List<LlmCostBudgetDO> locked) {
        var ids = locked.stream().map(LlmCostBudgetDO::getServiceId).collect(java.util.stream.Collectors.toSet());
        if (!ids.containsAll(budgets.currentParticipants(requestId))) throw new ParticipantSetChangedException();
    }
    static final class ParticipantSetChangedException extends RuntimeException { }
    public void unavailable(List<LlmCostBudgetDO> models, String reason) {
        for (var model : models) budgets.update(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(LlmCostBudgetDO.class)
                .eq(LlmCostBudgetDO::getId, model.getId())
                .set(LlmCostBudgetDO::getCostDataReason, reason));
    }
    public void register(Long tenantId, String requestId, Long target) {
        budgets.register(requestId, tenantId, target, time.local(time.now()));
    }
    public boolean complete(LlmCostBudgetDO config, Instant now) {
        if (reconciledBudgets != null && reconciledBudgets.enabled()) {
            return true;
        }
        var pending = budgets.unfinished(config.getTenantId(), config.getServiceId(), time.earliest(now));
        if (pending.size() > 1000) return false;
        if (pending.stream().anyMatch(row -> !"RUNNING".equals(row.getState()))) return false;
        return registry.confirmed(pending).size() == pending.size();
    }
    public boolean ready(LlmCostBudgetDO config, Instant now) {
        return costingEnabled && outboxProperties.isEnabled() && config.getCostDataReason() == null && complete(config, now);
    }
    public LlmCostStatusRespDTO.Full snapshot(LlmCostBudgetDO config, boolean pricingEligible) {
        Instant now = time.now();
        boolean available = ready(config, now);
        var staged = available ? stagedCosts(config.getTenantId(), now) : CostReconciliationBudgetRead.StagedCosts.EMPTY;
        return snapshot(config, pricingEligible, now, available, staged, p -> load(config, p));
    }
    private LlmCostStatusRespDTO.Full snapshot(LlmCostBudgetDO config, boolean pricingEligible, Instant now,
            boolean available, CostReconciliationBudgetRead.StagedCosts staged,
            java.util.function.Function<BudgetPeriodResolver.Period, CostBudgetPeriodDO> loader) {
        boolean enabled = Boolean.TRUE.equals(config.getCostLimitEnabled());
        String availability = config.getCostDataReason() == null ? "READY" : "UNAVAILABLE";
        if ("READY".equals(availability) && (!available || !staged.available())) availability = "UNAVAILABLE";
        boolean ready = "READY".equals(availability);
        List<ApiKeyCostStatusRespDTO.PeriodStatus> detail = new ArrayList<>();
        List<ApiKeyCostStatusRespDTO.Hit> hits = new ArrayList<>();
        for (var period : time.resolve(now)) {
            BigDecimal limit = limit(config, period.type());
            var row = ready ? staged.include(loader.apply(period), period, null, config.getServiceId()) : null;
            Boolean exceeded = row == null ? null : limit != null && row.getUsedCostFen().compareTo(limit) >= 0;
            boolean effective = enabled && Boolean.TRUE.equals(exceeded);
            if (effective) hits.add(new ApiKeyCostStatusRespDTO.Hit("LLM", period.type(), time.iso(period.end())));
            detail.add(new ApiKeyCostStatusRespDTO.PeriodStatus(period.type(), time.iso(period.start()), time.iso(period.end()),
                    amount(limit), row == null ? null : amount(row.getUsedCostFen()),
                    row == null || limit == null ? null : amount(limit.subtract(row.getUsedCostFen()).max(BigDecimal.ZERO)),
                    exceeded, effective, row == null ? null : String.valueOf(row.getCalculatedCount()),
                    row == null ? null : String.valueOf(row.getUnpricedCount()), row == null ? null : String.valueOf(row.getUncalculableCount()),
                    row == null ? null : String.valueOf(row.getNotChargeableCount())));
        }
        CostBudgetDecision decision = new CostBudgetDecision(hits, enabled && !ready);
        return new LlmCostStatusRespDTO.Full(String.valueOf(config.getServiceId()), "FULL", config(config),
                new LlmCostStatusRespDTO.Warning(Boolean.TRUE.equals(config.getWarningEnabled()), enabled && pricingEligible
                        && Boolean.TRUE.equals(config.getWarningEnabled()), config.getWarningThresholdPercent(), channels(config)),
                pricingEligible, "CNY", "Asia/Shanghai", List.of("API_KEY", "PLAYGROUND"), time.iso(time.local(now)),
                availability, !enabled ? "DISABLED" : !ready ? "UNKNOWN" : hits.isEmpty() ? "AVAILABLE" : "LIMITED",
                decision.retryAt(), detail, decision.limits());
    }
    /** 当前页同一时点批量主库读取，只有 READY 缺失累计时才进行账本覆盖重建。 */
    public List<LlmCostStatusRespDTO.Full> snapshots(Long tenantId, List<Long> ids, java.util.function.Supplier<Set<Long>> priceEligibility) {
        var configs = budgets.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(LlmCostBudgetDO.class)
                .eq(LlmCostBudgetDO::getTenantId, tenantId).in(LlmCostBudgetDO::getServiceId, ids)
                .orderByAsc(LlmCostBudgetDO::getServiceId).last("FOR UPDATE"));
        if (configs.size() != ids.size()) throw new CostBudgetUnavailableException();
        var eligible = priceEligibility.get();
        Instant now = time.now();
        boolean reconciliationActive = reconciledBudgets != null && reconciledBudgets.enabled();
        var staged = reconciliationActive && costingEnabled && outboxProperties.isEnabled()
                ? reconciledBudgets.staged(tenantId, now) : CostReconciliationBudgetRead.StagedCosts.EMPTY;
        var blocked = reconciliationActive ? Set.<Long>of() : blockedLegacyModels(tenantId, ids, now);
        var rows = periods.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(CostBudgetPeriodDO.class)
                .eq(CostBudgetPeriodDO::getTenantId, tenantId).eq(CostBudgetPeriodDO::getScopeType, "LLM")
                .in(CostBudgetPeriodDO::getScopeId, ids).ge(CostBudgetPeriodDO::getPeriodStart, time.earliest(now))
                .orderByAsc(CostBudgetPeriodDO::getScopeId).last("FOR UPDATE"));
        var indexed = new HashMap<String, CostBudgetPeriodDO>();
        rows.forEach(row -> indexed.put(row.getScopeId() + ":" + row.getPeriodType() + ":" + row.getPeriodStart(), row));
        var missing = configs.stream().filter(config -> (!reconciliationActive || costingEnabled && outboxProperties.isEnabled())
                        && staged.available() && config.getCostDataReason() == null
                        && !blocked.contains(config.getServiceId()))
                .filter(config -> time.resolve(now).stream().anyMatch(p -> !indexed.containsKey(config.getServiceId() + ":" + p.type() + ":" + p.start())))
                .map(LlmCostBudgetDO::getServiceId).toList();
        if (reconciliationActive && !missing.isEmpty()) {
            for (var config : configs) {
                if (!missing.contains(config.getServiceId())) {
                    continue;
                }
                for (var period : time.resolve(now)) {
                    String key = config.getServiceId() + ":" + period.type() + ":" + period.start();
                    if (!indexed.containsKey(key)) {
                        var rebuilt = empty(config, period);
                        reconciledBudgets.rebuild(rebuilt, null, config.getServiceId());
                        periods.insert(rebuilt);
                        indexed.put(key, rebuilt);
                    }
                }
            }
        } else if (!missing.isEmpty()) {
            var facts = settlements.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(CostBudgetSettlementDO.class)
                    .eq(CostBudgetSettlementDO::getTenantId, tenantId).in(CostBudgetSettlementDO::getServiceId, missing)
                    .ge(CostBudgetSettlementDO::getRequestStartedAt, time.earliest(now)).eq(CostBudgetSettlementDO::getBudgetApplied, true));
            var grouped = facts.stream().collect(java.util.stream.Collectors.groupingBy(CostBudgetSettlementDO::getServiceId));
            for (var config : configs) if (missing.contains(config.getServiceId())) for (var period : time.resolve(now)) {
                String key = config.getServiceId() + ":" + period.type() + ":" + period.start();
                if (indexed.containsKey(key)) continue;
                var rebuilt = empty(config, period);
                for (var fact : grouped.getOrDefault(config.getServiceId(), List.of()))
                    if (!fact.getRequestStartedAt().isBefore(period.start()) && fact.getRequestStartedAt().isBefore(period.end())) CostBudgetService.add(rebuilt, fact);
                periods.insert(rebuilt); indexed.put(key, rebuilt);
            }
        }
        return configs.stream().map(config -> snapshot(config, eligible.contains(config.getServiceId()), now,
                costingEnabled && outboxProperties.isEnabled() && config.getCostDataReason() == null && !blocked.contains(config.getServiceId()),
                staged,
                period -> {
                    var existing = indexed.get(config.getServiceId() + ":" + period.type() + ":" + period.start());
                    return existing == null ? load(config, period) : existing;
                })).toList();
    }

    private Set<Long> blockedLegacyModels(Long tenantId, List<Long> ids, Instant now) {
        var pending = budgets.unfinishedBatch(tenantId, ids, time.earliest(now));
        if (pending.size() >= 10001) {
            return Set.copyOf(ids);
        }
        var blocked = new HashSet<Long>();
        var counts = new HashMap<Long, Integer>();
        var confirmed = registry.confirmed(pending.stream().filter(row -> "RUNNING".equals(row.getState())).toList());
        for (var row : pending) {
            int count = counts.merge(row.getServiceId(), 1, Integer::sum);
            if (count > 1000 || !"RUNNING".equals(row.getState()) || !confirmed.contains(row.getRequestId())) {
                blocked.add(row.getServiceId());
            }
        }
        return blocked;
    }
    public CostBudgetDecision decision(LlmCostBudgetDO config) {
        if (!Boolean.TRUE.equals(config.getCostLimitEnabled())) return new CostBudgetDecision(List.of(), false);
        var state = snapshot(config, true);
        return new CostBudgetDecision(state.limits(), "UNKNOWN".equals(state.budgetState()));
    }
    public CostBudgetPeriodDO load(LlmCostBudgetDO config, BudgetPeriodResolver.Period period) {
        var row = periods.lockScope(config.getTenantId(), "LLM", config.getServiceId(), period.type(), period.start());
        if (row != null) return row;
        row = empty(config, period);
        if (reconciledBudgets != null && reconciledBudgets.enabled()) {
            reconciledBudgets.rebuild(row, null, config.getServiceId());
        } else {
            for (var fact : settlements.modelHistory(config.getTenantId(), config.getServiceId(), period.start())) {
                if (Boolean.TRUE.equals(fact.getBudgetApplied()) && fact.getRequestStartedAt().isBefore(period.end())) {
                    CostBudgetService.add(row, fact);
                }
            }
        }
        periods.insert(row);
        return row;
    }
    CostBudgetPeriodDO empty(LlmCostBudgetDO config, BudgetPeriodResolver.Period period) {
        var row = new CostBudgetPeriodDO(); row.setId(SnowflakeIdUtil.nextId());
        row.setTenantId(config.getTenantId()); row.setScopeType("LLM"); row.setScopeId(config.getServiceId());
        row.setPeriodType(period.type()); row.setPeriodStart(period.start()); row.setPeriodEnd(period.end());
        row.setUsedCostFen(BigDecimal.ZERO); row.setCalculatedCount(0L); row.setUnpricedCount(0L);
        row.setUncalculableCount(0L); row.setNotChargeableCount(0L); row.setRebuildVersion(0L);
        row.setUpdateTime(time.local(time.now())); return row;
    }
    public void apply(LlmCostBudgetDO config, CostBudgetSettlementDO fact) {
        for (var period : time.resolve(fact.getRequestStartedAt().atZone(BudgetPeriodResolver.ZONE).toInstant())) {
            var row = load(config, period); CostBudgetService.add(row, fact);
            row.setUpdateTime(time.local(time.now())); periods.updateById(row);
        }
    }
    public void capture(LlmCostBudgetDO config) {
        var now = time.now();
        if (!Boolean.TRUE.equals(config.getCostLimitEnabled()) || !ready(config, now)) {
            return;
        }
        capture(config, now, stagedCosts(config.getTenantId(), now));
    }

    void capture(LlmCostBudgetDO config, Instant now, CostReconciliationBudgetRead.StagedCosts staged) {
        if (staged.available()) {
            alerts.capture(config, time.resolve(now).stream()
                    .map(p -> staged.include(load(config, p), p, null, config.getServiceId())).toList());
        }
    }

    CostReconciliationBudgetRead.StagedCosts stagedCosts(Long tenantId, Instant now) {
        return CostReconciliationBudgetRead.stagedWhenEnabled(reconciledBudgets, tenantId, now);
    }
    public static BigDecimal limit(LlmCostBudgetDO row, String period) {
        return switch (period) { case "DAY" -> row.getDailyLimitFen(); case "WEEK" -> row.getWeeklyLimitFen(); default -> row.getMonthlyLimitFen(); };
    }
    public static CostLimitConfig config(LlmCostBudgetDO row) {
        return new CostLimitConfig(Boolean.TRUE.equals(row.getCostLimitEnabled()), amount(row.getDailyLimitFen()),
                amount(row.getWeeklyLimitFen()), amount(row.getMonthlyLimitFen()), String.valueOf(row.getConfigVersion()));
    }
    public static List<String> channels(LlmCostBudgetDO row) { return JSON.parseArray(row.getWarningChannels(), String.class); }
    public static String amount(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
