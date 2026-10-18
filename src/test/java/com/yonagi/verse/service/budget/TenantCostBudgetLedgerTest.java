package com.yonagi.verse.service.budget;

import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dao.projection.TenantCostBudgetAggregate;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    TenantCostBudgetAggregate totals(String amount, long calculated, long unpriced, long uncalculable, long notChargeable) {
        var result = new TenantCostBudgetAggregate(); result.setUsedCostFen(new BigDecimal(amount));
        result.setCalculatedCount(BigDecimal.valueOf(calculated)); result.setUnpricedCount(BigDecimal.valueOf(unpriced));
        result.setUncalculableCount(BigDecimal.valueOf(uncalculable)); result.setNotChargeableCount(BigDecimal.valueOf(notChargeable));
        result.setInvalidCount(BigDecimal.ZERO); return result;
    }
    @Test void previewAggregatesExactSnapshotsWithoutWritesAndWeekOutlivesMonth() {
        when(facts.aggregateTenant(eq(2L),any(),any())).thenReturn(totals("100.000",2,1,0,0));
        var decision = ledger.preview(config);
        assertEquals(3,decision.limits().size()); assertEquals("WEEK",decision.limits().getFirst().period());
        assertEquals("2026-10-05T00:00+08:00",decision.retryAt());
        verifyNoInteractions(periods,alerts); assertThrows(CostLimitExceededException.class,decision::enforce);
        for (var period : time.resolve(time.now())) verify(facts).aggregateTenant(2L,period.start(),period.end());
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
        when(facts.aggregateTenant(eq(2L),any(),any())).thenReturn(totals("0.0001",1,1,1,1));
        var full = ledger.snapshot(config,"7");
        assertEquals("AVAILABLE",full.budgetState()); assertEquals("7",full.observedSettingsVersion());
        var day = full.periods().getFirst(); assertEquals("0.0001",day.usedCostFen()); assertEquals("99.9999",day.remainingCostFen());
        assertEquals("1",day.calculatedCount()); assertEquals("1",day.unpricedCount()); assertEquals("1",day.uncalculableCount()); assertEquals("1",day.notChargeableCount());
    }
    @Test void configurationMissingIsUnavailableAndDisabledPreviewDoesNotReadHistory() {
        assertThrows(CostBudgetUnavailableException.class, () -> ledger.lock(2L));
        config.setCostLimitEnabled(false); assertEquals(List.of(),ledger.preview(config).limits()); verifyNoInteractions(facts,periods);
    }
    @Test void emptyHistoryRebuildsZeroConsumptionWithoutLimitingTenant() {
        when(facts.aggregateTenant(eq(2L),any(),any())).thenReturn(totals("0",0,0,0,0));
        var full = ledger.snapshot(config,"7");
        assertEquals("AVAILABLE",full.budgetState());
        for (var period : full.periods()) {
            assertEquals("0",period.usedCostFen()); assertEquals("100",period.remainingCostFen());
            assertEquals("0",period.calculatedCount()); assertEquals("0",period.unpricedCount());
            assertEquals("0",period.uncalculableCount()); assertEquals("0",period.notChargeableCount());
            assertFalse(period.exceeded());
        }
        verify(periods,times(3)).insert(any(CostBudgetPeriodDO.class));
    }
    @Test void invalidHistoryCannotBecomeZeroConsumptionOrRebuildAPeriod() {
        var invalid = totals("0",0,0,0,0); invalid.setInvalidCount(BigDecimal.ONE);
        when(facts.aggregateTenant(eq(2L),any(),any())).thenReturn(invalid);
        assertThrows(IllegalStateException.class, () -> ledger.preview(config));
        assertThrows(IllegalStateException.class, () -> ledger.load(config,time.resolve(time.now()).getFirst()));
        verify(periods,never()).insert(any(CostBudgetPeriodDO.class)); verifyNoInteractions(alerts);
    }
    @ParameterizedTest
    @ValueSource(strings={"100000000000000000000000000000000000000000000000", "0.0000000000000000001"})
    void unsupportedAmountPrecisionFailsWithoutWriting(String amount) {
        when(facts.aggregateTenant(eq(2L),any(),any())).thenReturn(totals(amount,1,0,0,0));
        assertThrows(IllegalStateException.class, () -> ledger.load(config,time.resolve(time.now()).getFirst()));
        verify(periods,never()).insert(any(CostBudgetPeriodDO.class));
    }
    @Test void maximumSupportedPrecisionAndCountsRemainExact() {
        String amount = "99999999999999999999999999999999999999999999999.123456789012345678";
        var totals = totals(amount,Long.MAX_VALUE,2,3,4);
        when(facts.aggregateTenant(eq(2L),any(),any())).thenReturn(totals);
        var row = ledger.load(config,time.resolve(time.now()).getFirst());
        assertEquals(new BigDecimal(amount),row.getUsedCostFen()); assertEquals(Long.MAX_VALUE,row.getCalculatedCount());
        assertEquals(2L,row.getUnpricedCount()); assertEquals(3L,row.getUncalculableCount()); assertEquals(4L,row.getNotChargeableCount());
        verify(periods).insert(row);
    }
    @Test void counterOverflowCannotWrapIntoValidConsumption() {
        var overflow = totals("1",0,0,0,0); overflow.setCalculatedCount(new BigDecimal("9223372036854775808"));
        when(facts.aggregateTenant(eq(2L),any(),any())).thenReturn(overflow);
        assertThrows(ArithmeticException.class, () -> ledger.load(config,time.resolve(time.now()).getFirst()));
        verify(periods,never()).insert(any(CostBudgetPeriodDO.class));
    }
    @Test void databaseFailureDoesNotProducePreviewOrPersistZeroPeriod() {
        when(facts.aggregateTenant(eq(2L),any(),any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> ledger.preview(config));
        assertThrows(IllegalStateException.class, () -> ledger.load(config,time.resolve(time.now()).getFirst()));
        verify(periods,never()).insert(any(CostBudgetPeriodDO.class)); verifyNoInteractions(alerts);
    }
    @Test void appliedHistoryAndCurrentPendingFactAreAddedOnceWhenPeriodIsMissing() {
        when(facts.aggregateTenant(eq(2L),any(),any())).thenReturn(totals("99.999",2,1,0,0));
        var pending = fact("CALCULATED","0.001"); pending.setBudgetApplied(false);
        ledger.apply(config,pending);
        var rows = org.mockito.ArgumentCaptor.forClass(CostBudgetPeriodDO.class);
        verify(periods,times(3)).updateById(rows.capture());
        for (var row : rows.getAllValues()) {
            assertEquals(0,new BigDecimal("100").compareTo(row.getUsedCostFen()));
            assertEquals(3L,row.getCalculatedCount()); assertEquals(1L,row.getUnpricedCount());
        }
    }
    @Test void existingCumulativePeriodDoesNotReadHistoryAgain() {
        var period = time.resolve(time.now()).getFirst();
        var existing = new CostBudgetPeriodDO(); existing.setUsedCostFen(new BigDecimal("17"));
        when(periods.lockScope(2L,"TENANT",2L,period.type(),period.start())).thenReturn(existing);
        assertSame(existing,ledger.load(config,period)); verifyNoInteractions(facts);
        verify(periods,never()).insert(any(CostBudgetPeriodDO.class));
    }
}
