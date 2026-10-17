package com.yonagi.verse.service.budget;

import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅验证额度业务决策和累计口径，不证明数据库锁与原子性。 */
class TenantCostBudgetLedgerTest {
    final TenantCostBudgetMapper budgets = mock(TenantCostBudgetMapper.class);
    final CostBudgetSettlementMapper facts = mock(CostBudgetSettlementMapper.class);
    final CostBudgetInvocationMapper invocations = mock(CostBudgetInvocationMapper.class);
    final CostBudgetPeriodMapper periods = mock(CostBudgetPeriodMapper.class);
    final BudgetExecutionRegistry registry = mock(BudgetExecutionRegistry.class);
    final BudgetPeriodResolver time = new BudgetPeriodResolver(Clock.fixed(Instant.parse("2026-09-30T08:00:00Z"),ZoneOffset.UTC));
    final LlmCostAlertCaptureService alerts = mock(LlmCostAlertCaptureService.class);
    final TenantCostBudgetLedger ledger = new TenantCostBudgetLedger(budgets,facts,invocations,periods,registry,time,new UsageOutboxProperties(),alerts);
    TenantCostBudgetDO config;
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(ledger,"costingEnabled",true);
        config = new TenantCostBudgetDO(); config.setTenantId(2L); config.setConfigVersion(0L); config.setCostLimitEnabled(true);
        config.setDailyLimitFen(new BigDecimal("100")); config.setWeeklyLimitFen(new BigDecimal("100")); config.setMonthlyLimitFen(new BigDecimal("100"));
        config.setWarningEnabled(false); config.setWarningThresholdPercent(80); config.setWarningChannels("[]");
        when(invocations.tenantUnfinished(eq(2L),any())).thenReturn(List.of());
        when(registry.confirmed(any())).thenReturn(Set.of());
    }
    CostBudgetSettlementDO fact(String status, String amount) {
        var row = new CostBudgetSettlementDO(); row.setTenantId(2L); row.setCostStatus(status); row.setBudgetApplied(true);
        row.setEstimatedCostFen(amount == null ? null : new BigDecimal(amount)); row.setRequestStartedAt(time.local(time.now())); return row;
    }
    @Test void previewAggregatesExactSnapshotsWithoutWritesAndWeekOutlivesMonth() {
        when(facts.tenantHistory(eq(2L),any(),any())).thenReturn(List.of(fact("CALCULATED","99.999"),fact("CALCULATED","0.001"),fact("UNPRICED",null)));
        var decision = ledger.preview(config);
        assertEquals(3,decision.limits().size()); assertEquals("WEEK",decision.limits().getFirst().period());
        assertEquals("2026-10-05T00:00+08:00",decision.retryAt());
        verifyNoInteractions(periods,alerts); assertThrows(CostLimitExceededException.class,decision::enforce);
    }
    @Test void unknownExecutionBlocksEnabledButDisabledRemainsDisabledAndHidesNumbers() {
        config.setCostDataReason("SNAPSHOT_CONFLICT");
        var enabled = ledger.snapshot(config,"3"); assertEquals("UNKNOWN",enabled.budgetState());
        assertTrue(enabled.periods().stream().allMatch(p -> p.usedCostFen() == null && p.calculatedCount() == null && p.exceeded() == null));
        assertThrows(CostBudgetUnavailableException.class, () -> ledger.decision(config).enforce());
        config.setCostLimitEnabled(false); assertEquals("DISABLED",ledger.snapshot(config,"3").budgetState());
        assertFalse(ledger.decision(config).unavailable()); verifyNoInteractions(facts,periods);
    }
    @Test void runningUnconfirmedAndFinalizingAreNotZeroConsumption() {
        var pending = new CostBudgetInvocationDO(); pending.setState("RUNNING");
        when(invocations.tenantUnfinished(eq(2L),any())).thenReturn(List.of(pending));
        assertTrue(ledger.preview(config).unavailable());
        pending.setState("UNKNOWN"); assertTrue(ledger.preview(config).unavailable());
        pending.setState("FINALIZING"); assertTrue(ledger.preview(config).unavailable());
        verifyNoInteractions(facts,periods);
    }
    @Test void historyIncludesAllSourcesAndNonCalculatedCountsWithoutRepricing() {
        when(facts.tenantHistory(eq(2L),any(),any())).thenReturn(List.of(fact("CALCULATED","0.0001"),fact("UNPRICED",null),fact("UNCALCULABLE",null),fact("NOT_CHARGEABLE",null)));
        var full = ledger.snapshot(config,"7");
        assertEquals("AVAILABLE",full.budgetState()); assertEquals("7",full.observedSettingsVersion());
        var day = full.periods().getFirst(); assertEquals("0.0001",day.usedCostFen()); assertEquals("99.9999",day.remainingCostFen());
        assertEquals("1",day.calculatedCount()); assertEquals("1",day.unpricedCount()); assertEquals("1",day.uncalculableCount()); assertEquals("1",day.notChargeableCount());
    }
    @Test void configurationMissingIsUnavailableAndDisabledPreviewDoesNotReadHistory() {
        assertThrows(CostBudgetUnavailableException.class, () -> ledger.lock(2L));
        config.setCostLimitEnabled(false); assertEquals(List.of(),ledger.preview(config).limits()); verifyNoInteractions(facts,periods);
    }
}
