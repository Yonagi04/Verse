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

/** 调用者先锁租户配置；费用与准入共用主库真相，不缓存。 */
@Service @RequiredArgsConstructor
public class TenantCostBudgetLedger {
    private final TenantCostBudgetMapper budgets;
    private final CostBudgetSettlementMapper settlements;
    private final CostBudgetInvocationMapper invocations;
    private final CostBudgetPeriodMapper periods;
    private final BudgetExecutionRegistry registry;
    private final BudgetPeriodResolver time;
    private final UsageOutboxProperties outbox;
    private final LlmCostAlertCaptureService alerts;
    @Value("${verse.llm.costing.enabled:true}") private boolean costingEnabled;
    private CostReconciliationBudgetRead reconciledBudgets;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReconciledBudgets(CostReconciliationBudgetRead read) {
        this.reconciledBudgets = read;
    }

    public TenantCostBudgetDO lock(Long tenantId) {
        var row = budgets.lock(tenantId);
        if (row == null) throw new CostBudgetUnavailableException();
        return row;
    }
    public void create(Long tenantId) {
        var row = new TenantCostBudgetDO(); row.setId(SnowflakeIdUtil.nextId()); row.setTenantId(tenantId);
        row.setCostLimitEnabled(false); row.setWarningEnabled(false); row.setWarningThresholdPercent(80);
        row.setWarningChannels("[]"); row.setConfigVersion(0L); row.setCreateTime(time.local(time.now()));
        row.setUpdateTime(row.getCreateTime()); budgets.insert(row);
    }
    public boolean ready(TenantCostBudgetDO config, Instant now) {
        if (!costingEnabled || !outbox.isEnabled() || config.getCostDataReason() != null) return false;
        if (reconciledBudgets != null && reconciledBudgets.enabled()) {
            return true;
        }
        var pending = invocations.tenantUnfinished(config.getTenantId(), time.earliest(now));
        return pending.size() <= 1000 && pending.stream().allMatch(row -> "RUNNING".equals(row.getState()))
                && registry.confirmed(pending).size() == pending.size();
    }
    public CostBudgetDecision decision(TenantCostBudgetDO config) {
        if (!Boolean.TRUE.equals(config.getCostLimitEnabled())) return new CostBudgetDecision(List.of(), false);
        var state = snapshot(config, null);
        return new CostBudgetDecision(state.limits(), "UNKNOWN".equals(state.budgetState()));
    }
    /** 确认预览只读费用事实，不重建 period、不触发写入。 */
    public CostBudgetDecision preview(TenantCostBudgetDO config) {
        if (!Boolean.TRUE.equals(config.getCostLimitEnabled())) return new CostBudgetDecision(List.of(), false);
        var now = time.now();
        if (!ready(config, now)) return new CostBudgetDecision(List.of(), true);
        var staged = stagedCosts(config.getTenantId(), now);
        if (!staged.available()) return new CostBudgetDecision(List.of(), true);
        List<ApiKeyCostStatusRespDTO.Hit> hits = new ArrayList<>();
        for (var p : time.resolve(now)) {
            var row = staged.include(aggregate(config, p), p, null, null);
            var limit = limit(config, p.type());
            if (limit != null && row.getUsedCostFen().compareTo(limit) >= 0)
                hits.add(new ApiKeyCostStatusRespDTO.Hit("TENANT", p.type(), time.iso(p.end())));
        }
        return new CostBudgetDecision(hits, false);
    }
    public TenantCostStatusRespDTO.Full snapshot(TenantCostBudgetDO config, String settingsVersion) {
        var now = time.now();
        boolean ready = ready(config, now);
        var staged = ready ? stagedCosts(config.getTenantId(), now) : CostReconciliationBudgetRead.StagedCosts.EMPTY;
        boolean available = ready && staged.available(), enabled = Boolean.TRUE.equals(config.getCostLimitEnabled());
        List<ApiKeyCostStatusRespDTO.PeriodStatus> details = new ArrayList<>();
        List<ApiKeyCostStatusRespDTO.Hit> hits = new ArrayList<>();
        for (var p : time.resolve(now)) {
            var limit = limit(config, p.type());
            var row = available ? staged.include(load(config, p), p, null, null) : null;
            Boolean exceeded = row == null ? null : limit != null && row.getUsedCostFen().compareTo(limit) >= 0;
            boolean effective = enabled && Boolean.TRUE.equals(exceeded);
            if (effective) hits.add(new ApiKeyCostStatusRespDTO.Hit("TENANT", p.type(), time.iso(p.end())));
            details.add(new ApiKeyCostStatusRespDTO.PeriodStatus(p.type(), time.iso(p.start()), time.iso(p.end()), amount(limit),
                    row == null ? null : amount(row.getUsedCostFen()), row == null || limit == null ? null : amount(limit.subtract(row.getUsedCostFen()).max(BigDecimal.ZERO)),
                    exceeded, effective, row == null ? null : String.valueOf(row.getCalculatedCount()), row == null ? null : String.valueOf(row.getUnpricedCount()),
                    row == null ? null : String.valueOf(row.getUncalculableCount()), row == null ? null : String.valueOf(row.getNotChargeableCount())));
        }
        var decision = new CostBudgetDecision(hits, enabled && !available);
        return new TenantCostStatusRespDTO.Full(String.valueOf(config.getTenantId()), "FULL", settingsVersion, config(config), warning(config),
                "CNY", "Asia/Shanghai", List.of("API_KEY", "PLAYGROUND"), time.iso(time.local(now)), available ? "READY" : "UNAVAILABLE",
                !enabled ? "DISABLED" : !available ? "UNKNOWN" : hits.isEmpty() ? "AVAILABLE" : "LIMITED",
                decision.retryAt(), details, decision.limits(),
                reconciledBudgets != null && reconciledBudgets.enabled() ? reconciledBudgets.summary(config.getTenantId(), now) : null);
    }
    public CostBudgetPeriodDO load(TenantCostBudgetDO config, BudgetPeriodResolver.Period period) {
        var row = periods.lockScope(config.getTenantId(), "TENANT", config.getTenantId(), period.type(), period.start());
        if (row != null) return row;
        row = aggregate(config, period); periods.insert(row); return row;
    }
    private CostBudgetPeriodDO aggregate(TenantCostBudgetDO config, BudgetPeriodResolver.Period period) {
        var row = new CostBudgetPeriodDO(); row.setId(SnowflakeIdUtil.nextId()); row.setTenantId(config.getTenantId());
        row.setScopeType("TENANT"); row.setScopeId(config.getTenantId()); row.setPeriodType(period.type());
        row.setPeriodStart(period.start()); row.setPeriodEnd(period.end());
        row.setRebuildVersion(0L); row.setUpdateTime(time.local(time.now()));
        if (reconciledBudgets != null && reconciledBudgets.enabled()) {
            reconciledBudgets.rebuild(row, null, null);
        } else {
            CostReconciliationBudgetRead.populate(row, settlements.aggregateTenant(config.getTenantId(), period.start(), period.end()));
        }
        return row;
    }
    public void apply(TenantCostBudgetDO config, CostBudgetSettlementDO fact) {
        for (var p : time.resolve(fact.getRequestStartedAt().atZone(BudgetPeriodResolver.ZONE).toInstant())) {
            var row = load(config, p); CostBudgetService.add(row, fact); row.setUpdateTime(time.local(time.now())); periods.updateById(row);
        }
    }
    public void capture(TenantCostBudgetDO config) {
        var now = time.now();
        if (!Boolean.TRUE.equals(config.getCostLimitEnabled()) || !ready(config, now)) {
            return;
        }
        capture(config, now, stagedCosts(config.getTenantId(), now));
    }

    void capture(TenantCostBudgetDO config, Instant now, CostReconciliationBudgetRead.StagedCosts staged) {
        if (staged.available()) {
            alerts.capture(config, time.resolve(now).stream().map(p -> staged.include(load(config, p), p, null, null)).toList());
        }
    }

    CostReconciliationBudgetRead.StagedCosts stagedCosts(Long tenantId, Instant now) {
        return CostReconciliationBudgetRead.stagedWhenEnabled(reconciledBudgets, tenantId, now);
    }
    public void unavailable(TenantCostBudgetDO config, String reason) {
        config.setCostDataReason(reason); budgets.updateById(config);
    }
    public static BigDecimal limit(TenantCostBudgetDO row, String period) {
        return switch (period) { case "DAY" -> row.getDailyLimitFen(); case "WEEK" -> row.getWeeklyLimitFen(); default -> row.getMonthlyLimitFen(); };
    }
    public static CostLimitConfig config(TenantCostBudgetDO row) {
        return new CostLimitConfig(Boolean.TRUE.equals(row.getCostLimitEnabled()), amount(row.getDailyLimitFen()), amount(row.getWeeklyLimitFen()),
                amount(row.getMonthlyLimitFen()), String.valueOf(row.getConfigVersion()));
    }
    public static List<String> channels(TenantCostBudgetDO row) { return JSON.parseArray(row.getWarningChannels(), String.class); }
    public static LlmCostStatusRespDTO.Warning warning(TenantCostBudgetDO row) {
        return new LlmCostStatusRespDTO.Warning(Boolean.TRUE.equals(row.getWarningEnabled()), Boolean.TRUE.equals(row.getCostLimitEnabled())
                && Boolean.TRUE.equals(row.getWarningEnabled()), row.getWarningThresholdPercent(), channels(row));
    }
    private static String amount(BigDecimal value) { return LlmCostBudgetLedger.amount(value); }
}
