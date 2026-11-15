package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.CostBudgetPeriodDO;
import com.yonagi.verse.dao.projection.PendingCostReconciliation;
import com.yonagi.verse.dao.projection.TenantCostBudgetAggregate;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CostReconciliationBudgetReadTest {
    private final CostBudgetSettlementMapper settlements = mock(CostBudgetSettlementMapper.class);
    private final CostReconciliationCaseMapper cases = mock(CostReconciliationCaseMapper.class);
    private final CostReconciliationTracking tracking = mock(CostReconciliationTracking.class);
    private final CostReconciliationBudgetRead service = new CostReconciliationBudgetRead(
            tracking, settlements, cases, new BudgetPeriodResolver());

    @Test
    void identityConflictAnd1001ConfirmedFactsFailClosed() {
        var instant = Instant.parse("2026-11-14T00:00:00Z");
        when(settlements.hasInvalidPendingConfirmed(eq(7L), any(), any())).thenReturn(true);
        assertFalse(service.staged(7L, instant).available());
        when(settlements.hasInvalidPendingConfirmed(eq(7L), any(), any())).thenReturn(false);
        when(settlements.pendingConfirmed(eq(7L), any(), any()))
                .thenReturn(Collections.nCopies(1001, new CostBudgetSettlementDO()));
        assertFalse(service.staged(7L, instant).available());
        var fact = new CostBudgetSettlementDO(); fact.setEventId("confirmed-original");
        when(settlements.pendingConfirmed(eq(7L), any(), any())).thenReturn(List.of(fact));
        var result = service.staged(7L, instant);
        assertTrue(result.available());
        assertEquals(List.of(fact), result.rows());
    }

    @Test
    void temporaryTotalsRespectTimeKeyAndModelWithoutChangingCommittedLedger() {
        var start = LocalDateTime.of(2026, 11, 8, 0, 0);
        var period = new BudgetPeriodResolver.Period("DAY", start, start.plusDays(1));
        var committed = new CostBudgetPeriodDO();
        committed.setPeriodType("DAY");
        committed.setPeriodStart(start);
        committed.setPeriodEnd(period.end());
        committed.setUsedCostFen(new BigDecimal("10.01"));
        committed.setCalculatedCount(2L);
        committed.setUnpricedCount(3L);
        committed.setUncalculableCount(4L);
        committed.setNotChargeableCount(5L);
        var staged = new CostReconciliationBudgetRead.StagedCosts(true, List.of(
                stagedFact(start.minusNanos(1), 3L, 10L), stagedFact(start, 3L, 10L),
                stagedFact(start.plusHours(1), 4L, 10L), stagedFact(start.plusHours(2), 3L, 11L),
                stagedFact(period.end(), 3L, 10L)));

        var tenant = staged.include(committed, period, null, null);
        var keyAndModel = staged.include(committed, period, 3L, 10L);

        assertEquals(0, new BigDecimal("10.04").compareTo(tenant.getUsedCostFen()));
        assertEquals(5L, tenant.getCalculatedCount());
        assertEquals(0, new BigDecimal("10.02").compareTo(keyAndModel.getUsedCostFen()));
        assertEquals(3L, keyAndModel.getCalculatedCount());
        assertEquals(3L, keyAndModel.getUnpricedCount());
        assertEquals(4L, keyAndModel.getUncalculableCount());
        assertEquals(5L, keyAndModel.getNotChargeableCount());
        assertEquals("DAY", keyAndModel.getPeriodType());
        assertEquals(period.end(), keyAndModel.getPeriodEnd());
        assertEquals(new BigDecimal("10.01"), committed.getUsedCostFen());
        assertEquals(2L, committed.getCalculatedCount());
        assertSame(committed, staged.include(committed, period, 99L, null));
        assertThrows(CostBudgetUnavailableException.class,
                () -> CostReconciliationBudgetRead.StagedCosts.UNAVAILABLE.include(committed, period, null, null));
        committed.setCalculatedCount(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> staged.include(committed, period, 3L, 10L));
        verifyNoInteractions(settlements);
    }

    private CostBudgetSettlementDO stagedFact(LocalDateTime at, Long keyId, Long modelId) {
        var fact = new CostBudgetSettlementDO();
        fact.setRequestStartedAt(at);
        fact.setApiKeyId(keyId);
        fact.setServiceId(modelId);
        fact.setEstimatedCostFen(new BigDecimal("0.01"));
        return fact;
    }

    @Test
    void effectiveLedgerAndPendingSummaryPreserveExactAmountsAndClassification() {
        var instant = Instant.parse("2026-11-08T07:00:00Z");
        var period = new BudgetPeriodResolver().resolve(instant).getFirst();
        var row = new CostBudgetPeriodDO();
        row.setTenantId(7L);
        row.setPeriodStart(period.start());
        row.setPeriodEnd(period.end());
        var totals = new TenantCostBudgetAggregate();
        totals.setUsedCostFen(new BigDecimal("12.3400"));
        totals.setCalculatedCount(new BigDecimal("2"));
        totals.setUnpricedCount(BigDecimal.ONE);
        totals.setUncalculableCount(BigDecimal.ZERO);
        totals.setNotChargeableCount(new BigDecimal("3"));
        totals.setInvalidCount(BigDecimal.ZERO);
        when(settlements.aggregateEffective(7L, period.start(), period.end(), 3L, 10L)).thenReturn(totals);
        var pending = new PendingCostReconciliation();
        pending.setPendingCount(4L);
        pending.setEstimatedCostFen(new BigDecimal("1.2300"));
        when(cases.pendingSummary(eq(7L), any())).thenReturn(pending);

        service.rebuild(row, 3L, 10L);
        var summary = service.summary(7L, instant);

        assertEquals(new BigDecimal("12.3400"), row.getUsedCostFen());
        assertEquals(2L, row.getCalculatedCount());
        assertEquals(1L, row.getUnpricedCount());
        assertEquals(0L, row.getUncalculableCount());
        assertEquals(3L, row.getNotChargeableCount());
        assertEquals(4L, summary.pendingCount());
        assertEquals("1.23", summary.estimatedCostFen());
        assertEquals(List.of(), CostReconciliationBudgetRead.stagedWhenEnabled(service, 7L, instant).rows());
        when(tracking.enabled()).thenReturn(true);
        when(settlements.hasInvalidPendingConfirmed(eq(7L), any(), any())).thenReturn(true);
        assertFalse(CostReconciliationBudgetRead.stagedWhenEnabled(service, 7L, instant).available());
    }
}
