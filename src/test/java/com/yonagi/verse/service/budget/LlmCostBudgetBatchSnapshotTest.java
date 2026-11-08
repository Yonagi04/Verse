package com.yonagi.verse.service.budget;

import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.dao.entity.CostBudgetPeriodDO;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.LlmCostBudgetDO;
import com.yonagi.verse.dao.mapper.CostBudgetPeriodMapper;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.LlmCostBudgetMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LlmCostBudgetBatchSnapshotTest {
    private final BudgetPeriodResolver time = new BudgetPeriodResolver(
            Clock.fixed(Instant.parse("2026-11-08T07:00:00Z"), ZoneOffset.UTC));
    private final LlmCostBudgetMapper configs = mock(LlmCostBudgetMapper.class);
    private final CostBudgetPeriodMapper periods = mock(CostBudgetPeriodMapper.class);
    private final CostBudgetSettlementMapper settlements = mock(CostBudgetSettlementMapper.class);
    private final CostReconciliationBudgetRead read = mock(CostReconciliationBudgetRead.class);
    private final LlmCostBudgetLedger ledger = new LlmCostBudgetLedger(configs, settlements, periods,
            mock(BudgetExecutionRegistry.class), time, new UsageOutboxProperties(), mock(LlmCostAlertCaptureService.class));

    @Test
    void activeReconciliationReadsCompletePageOnceAndFiltersStagedAmountByModel() {
        ReflectionTestUtils.setField(ledger, "costingEnabled", true);
        ledger.setReconciledBudgets(read);
        when(read.enabled()).thenReturn(true);
        when(configs.selectList(any())).thenReturn(List.of(config(10L), config(20L)));
        var rows = new ArrayList<CostBudgetPeriodDO>();
        for (long serviceId : List.of(10L, 20L)) {
            for (var period : time.resolve(time.now())) {
                rows.add(period(serviceId, period));
            }
        }
        when(periods.selectList(any())).thenReturn(rows);
        var staged = new CostBudgetSettlementDO();
        staged.setServiceId(10L);
        staged.setRequestStartedAt(time.local(time.now()));
        staged.setEstimatedCostFen(new BigDecimal("60"));
        when(read.staged(2L, time.now())).thenReturn(new CostReconciliationBudgetRead.StagedCosts(true, List.of(staged)));

        var status = ledger.snapshots(2L, List.of(10L, 20L), () -> Set.of(10L, 20L));

        assertEquals("LIMITED", status.getFirst().budgetState());
        assertEquals("100", status.getFirst().periods().getFirst().usedCostFen());
        assertEquals("AVAILABLE", status.getLast().budgetState());
        assertEquals("40", status.getLast().periods().getFirst().usedCostFen());
        verify(periods).selectList(any());
        verify(periods, never()).lockScope(any(), any(), any(), any(), any());
        verify(read).staged(2L, time.now());
        verify(read, never()).rebuild(any(), any(), any());
        verifyNoInteractions(settlements);
    }

    private LlmCostBudgetDO config(long serviceId) {
        var row = new LlmCostBudgetDO();
        row.setTenantId(2L);
        row.setServiceId(serviceId);
        row.setCostLimitEnabled(true);
        row.setDailyLimitFen(new BigDecimal("100"));
        row.setConfigVersion(0L);
        row.setWarningThresholdPercent(80);
        row.setWarningChannels("[]");
        return row;
    }

    private CostBudgetPeriodDO period(long serviceId, BudgetPeriodResolver.Period period) {
        var row = ledger.empty(config(serviceId), period);
        row.setUsedCostFen(new BigDecimal("40"));
        row.setCalculatedCount(1L);
        return row;
    }
}
