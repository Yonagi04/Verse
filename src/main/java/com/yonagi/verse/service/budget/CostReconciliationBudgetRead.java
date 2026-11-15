package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.CostBudgetPeriodDO;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.projection.TenantCostBudgetAggregate;
import com.yonagi.verse.dto.resp.TenantCostStatusRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class CostReconciliationBudgetRead {
    private static final int MAX_STAGED_ROWS = 1000;
    private final CostReconciliationTracking tracking;
    private final CostBudgetSettlementMapper settlements;
    private final CostReconciliationCaseMapper cases;
    private final BudgetPeriodResolver time;

    boolean enabled() {
        return tracking.enabled();
    }

    void rebuild(CostBudgetPeriodDO row, Long keyId, Long serviceId) {
        populate(row, settlements.aggregateEffective(row.getTenantId(), row.getPeriodStart(), row.getPeriodEnd(), keyId, serviceId));
    }

    TenantCostStatusRespDTO.Reconciliation summary(Long tenantId, Instant now) {
        var result = cases.pendingSummary(tenantId, time.earliest(now));
        return new TenantCostStatusRespDTO.Reconciliation(result.getPendingCount(),
                result.getEstimatedCostFen().stripTrailingZeros().toPlainString());
    }

    static void populate(CostBudgetPeriodDO row, TenantCostBudgetAggregate totals) {
        var used = totals.getUsedCostFen();
        if (totals.getInvalidCount().signum() != 0 || used.signum() < 0
                || used.precision() - used.scale() > 47 || used.scale() > 18) {
            throw new IllegalStateException("invalid budget settlement");
        }
        row.setUsedCostFen(used);
        row.setCalculatedCount(totals.getCalculatedCount().longValueExact());
        row.setUnpricedCount(totals.getUnpricedCount().longValueExact());
        row.setUncalculableCount(totals.getUncalculableCount().longValueExact());
        row.setNotChargeableCount(totals.getNotChargeableCount().longValueExact());
    }

    StagedCosts staged(Long tenantId, Instant instant) {
        var latestEnd = time.resolve(instant).stream().map(BudgetPeriodResolver.Period::end)
                .max(java.time.LocalDateTime::compareTo).orElseThrow();
        var from = time.earliest(instant);
        if (settlements.hasInvalidPendingConfirmed(tenantId, from, latestEnd)) {
            return StagedCosts.UNAVAILABLE;
        }
        var rows = settlements.pendingConfirmed(tenantId, from, latestEnd);
        if (rows.size() > MAX_STAGED_ROWS) {
            return StagedCosts.UNAVAILABLE;
        }
        return new StagedCosts(true, rows);
    }

    static StagedCosts stagedWhenEnabled(CostReconciliationBudgetRead reader, Long tenantId, Instant instant) {
        return reader != null && reader.enabled() ? reader.staged(tenantId, instant) : StagedCosts.EMPTY;
    }

    record StagedCosts(boolean available, List<CostBudgetSettlementDO> rows) {
        static final StagedCosts EMPTY = new StagedCosts(true, List.of());
        static final StagedCosts UNAVAILABLE = new StagedCosts(false, List.of());

        /** 临时合计仅供读取和告警，绝不传给累计写入入口。 */
        CostBudgetPeriodDO include(CostBudgetPeriodDO committed, BudgetPeriodResolver.Period period, Long keyId, Long serviceId) {
            if (!available) {
                throw new CostBudgetUnavailableException();
            }
            BigDecimal amount = committed.getUsedCostFen();
            long added = 0;
            for (var row : rows) {
                if (!row.getRequestStartedAt().isBefore(period.start()) && row.getRequestStartedAt().isBefore(period.end())
                        && (keyId == null || Objects.equals(keyId, row.getApiKeyId()))
                        && (serviceId == null || Objects.equals(serviceId, row.getServiceId()))) {
                    amount = amount.add(row.getEstimatedCostFen());
                    added++;
                }
            }
            if (added == 0) {
                return committed;
            }
            var result = new CostBudgetPeriodDO();
            result.setPeriodType(committed.getPeriodType());
            result.setPeriodStart(committed.getPeriodStart());
            result.setPeriodEnd(committed.getPeriodEnd());
            result.setUsedCostFen(amount);
            result.setCalculatedCount(Math.addExact(committed.getCalculatedCount(), added));
            result.setUnpricedCount(committed.getUnpricedCount());
            result.setUncalculableCount(committed.getUncalculableCount());
            result.setNotChargeableCount(committed.getNotChargeableCount());
            return result;
        }
    }
}
