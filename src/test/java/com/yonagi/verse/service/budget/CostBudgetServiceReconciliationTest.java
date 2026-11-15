package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostBudgetPeriodDO;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.LlmCostBudgetDO;
import com.yonagi.verse.dao.entity.TenantCostBudgetDO;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostBudgetPeriodMapper;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import com.yonagi.verse.service.pricing.CostResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 只验证预算业务决定和累计结果；不模拟证明事务、锁及 Outbox 原子性。 */
class CostBudgetServiceReconciliationTest {
    private static final Long TENANT_ID = 7L;
    private static final Long KEY_ID = 3L;
    private static final Long MODEL_ID = 10L;
    private final ApiKeyMapper keys = mock(ApiKeyMapper.class);
    private final CostBudgetInvocationMapper invocations = mock(CostBudgetInvocationMapper.class);
    private final CostBudgetSettlementMapper settlements = mock(CostBudgetSettlementMapper.class);
    private final CostBudgetPeriodMapper periods = mock(CostBudgetPeriodMapper.class);
    private final BudgetExecutionRegistry registry = mock(BudgetExecutionRegistry.class);
    private final UsageOutboxStager outbox = mock(UsageOutboxStager.class);
    private final UsageOutboxProperties properties = new UsageOutboxProperties();
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final LlmCostBudgetLedger models = mock(LlmCostBudgetLedger.class);
    private final TenantCostBudgetLedger tenants = mock(TenantCostBudgetLedger.class);
    private final CostReconciliationBudgetRead read = mock(CostReconciliationBudgetRead.class);
    private final CostReconciliationTracking tracking = mock(CostReconciliationTracking.class);
    private final BudgetPeriodResolver time = new BudgetPeriodResolver(
            Clock.fixed(Instant.parse("2026-11-08T07:00:00Z"), ZoneOffset.UTC));
    private final CostBudgetService service = new CostBudgetService(keys, invocations, settlements, periods,
            time, registry, outbox, properties, manager, models, tenants, mock(UserMapper.class));
    private final TenantCostBudgetDO tenant = new TenantCostBudgetDO();
    private final LlmCostBudgetDO model = new LlmCostBudgetDO();
    private final ApiKeyDO key = new ApiKeyDO();
    private final UserContext actor = new UserContext().setCurrentTenantId(TENANT_ID).setApiKeyId(KEY_ID);
    private final Map<String, CostBudgetPeriodDO> committed = new HashMap<>();

    @BeforeAll
    static void initializeKeyMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),
                "reconciled-budget-test"), ApiKeyDO.class);
    }

    @BeforeEach
    void setup() {
        service.setReconciledBudgets(read);
        service.setReconciliationTracking(tracking);
        ReflectionTestUtils.setField(service, "costingEnabled", true);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        tenant.setTenantId(TENANT_ID);
        tenant.setCostLimitEnabled(true);
        tenant.setDailyLimitFen(new BigDecimal("100"));
        tenant.setWeeklyLimitFen(new BigDecimal("100"));
        tenant.setMonthlyLimitFen(new BigDecimal("100"));
        model.setTenantId(TENANT_ID);
        model.setServiceId(MODEL_ID);
        model.setCostLimitEnabled(true);
        model.setDailyLimitFen(new BigDecimal("100"));
        model.setWeeklyLimitFen(new BigDecimal("100"));
        model.setMonthlyLimitFen(new BigDecimal("100"));
        key.setTenantId(TENANT_ID);
        key.setApiKeyId(KEY_ID);
        key.setCostLimitEnabled(true);
        key.setCostLimitDailyFen(new BigDecimal("100"));
        key.setCostLimitWeeklyFen(new BigDecimal("100"));
        key.setCostLimitMonthlyFen(new BigDecimal("100"));
        when(tenants.lock(TENANT_ID)).thenReturn(tenant);
        when(models.lock(TENANT_ID, MODEL_ID)).thenReturn(model);
        when(keys.lockBudgetKey(TENANT_ID, KEY_ID)).thenReturn(key);
        when(tenants.ready(eq(tenant), any())).thenReturn(true);
        when(models.ready(eq(model), any())).thenReturn(true);
        when(read.enabled()).thenReturn(true);
        when(read.staged(eq(TENANT_ID), any())).thenReturn(new CostReconciliationBudgetRead.StagedCosts(
                true, List.of(fact("60"))));
        when(tenants.load(eq(tenant), any())).thenAnswer(call -> cumulative("TENANT", call.getArgument(1)));
        when(models.load(any(), any())).thenAnswer(call -> cumulative(
                "LLM-" + call.getArgument(0, LlmCostBudgetDO.class).getServiceId(), call.getArgument(1)));
        when(periods.lockPeriod(eq(TENANT_ID), eq(KEY_ID), anyString(), any())).thenAnswer(call -> {
            var period = time.resolve(time.now()).stream()
                    .filter(value -> value.type().equals(call.getArgument(2))).findFirst().orElseThrow();
            return cumulative("API_KEY", period);
        });
    }

    @Test
    void confirmedStagingEnforcesAllScopesAndPeriodsWithoutPersistingTemporaryTotals() {
        var failure = assertThrows(CostLimitExceededException.class, () -> service.check(actor, MODEL_ID));
        assertEquals(Set.of("TENANT-DAY", "TENANT-WEEK", "TENANT-MONTH", "LLM-DAY", "LLM-WEEK", "LLM-MONTH",
                        "API_KEY-DAY", "API_KEY-WEEK", "API_KEY-MONTH"),
                failure.getStatus().limits().stream().map(hit -> hit.scope() + "-" + hit.period())
                        .collect(Collectors.toSet()));
        assertEquals(9, committed.size());
        assertTrue(committed.values().stream().allMatch(row -> row.getUsedCostFen().compareTo(new BigDecimal("40")) == 0
                && Long.valueOf(1L).equals(row.getCalculatedCount())));
        verify(periods, never()).updateById(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void amountBelowLimitAndDisabledScopeDoNotCreateFalseRejection() {
        when(read.staged(eq(TENANT_ID), any())).thenReturn(new CostReconciliationBudgetRead.StagedCosts(
                true, List.of(fact("59.999"))));
        service.check(actor, MODEL_ID);
        assertEquals(9, committed.size());
        assertTrue(committed.values().stream().allMatch(row -> row.getUsedCostFen().equals(new BigDecimal("40"))));
        tenant.setCostLimitEnabled(false);
        model.setCostLimitEnabled(false);
        key.setCostLimitEnabled(false);
        clearInvocations(read);
        service.check(actor, MODEL_ID);
        verify(read, never()).staged(any(), any());
    }

    @Test
    void playgroundWithoutKeyStillEnforcesTenantAndModelLimits() {
        actor.setApiKeyId(null);
        var failure = assertThrows(CostLimitExceededException.class, () -> service.check(actor, MODEL_ID));
        assertEquals(6, failure.getStatus().limits().size());
        assertTrue(failure.getStatus().limits().stream().noneMatch(hit -> "API_KEY".equals(hit.scope())));
        verifyNoInteractions(keys);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "model", "key-data", "costing", "outbox", "staging", "dependency"})
    void enabledBudgetWithIncompleteEvidenceFailsClosed(String reason) {
        switch (reason) {
            case "tenant" -> when(tenants.ready(eq(tenant), any())).thenReturn(false);
            case "model" -> when(models.ready(eq(model), any())).thenReturn(false);
            case "key-data" -> key.setCostDataReason("SNAPSHOT_CONFLICT");
            case "costing" -> ReflectionTestUtils.setField(service, "costingEnabled", false);
            case "outbox" -> properties.setEnabled(false);
            case "staging" -> when(read.staged(eq(TENANT_ID), any()))
                    .thenReturn(CostReconciliationBudgetRead.StagedCosts.UNAVAILABLE);
            case "dependency" -> when(read.staged(eq(TENANT_ID), any()))
                    .thenThrow(new IllegalStateException("confirmed facts unavailable"));
            default -> throw new IllegalArgumentException("unknown scenario");
        }
        assertThrows(CostBudgetUnavailableException.class, () -> service.check(actor, MODEL_ID));
        assertTrue(committed.isEmpty());
        verify(periods, never()).updateById(any());
    }

    @Test
    void disabledReconciliationPreservesLegacyBudgetDecision() {
        when(read.enabled()).thenReturn(false);
        when(tenants.decision(tenant)).thenReturn(new CostBudgetDecision(List.of(), true));
        when(models.decision(model)).thenReturn(new CostBudgetDecision(List.of(), false));
        key.setCostLimitEnabled(false);
        assertThrows(CostBudgetUnavailableException.class, () -> service.check(actor, MODEL_ID));
        verify(read, never()).staged(any(), any());
        assertTrue(committed.isEmpty());
    }

    @Test
    void correctionMovesContributionAcrossModelsForEveryPeriodAndScope() {
        var otherModel = new LlmCostBudgetDO();
        otherModel.setServiceId(11L);
        var before = fact("9");
        var after = fact("13");
        after.setServiceId(11L);

        service.adjustContribution(tenant, key, List.of(model, otherModel), before, after);

        for (var period : time.resolve(time.now())) {
            assertAmount("44", cumulative("TENANT", period));
            assertAmount("44", cumulative("API_KEY", period));
            assertAmount("31", cumulative("LLM-10", period));
            assertAmount("53", cumulative("LLM-11", period));
            assertEquals(0L, cumulative("LLM-10", period).getCalculatedCount());
            assertEquals(2L, cumulative("LLM-11", period).getCalculatedCount());
            assertEquals(1L, cumulative("TENANT", period).getCalculatedCount());
        }
        verify(periods, times(12)).updateById(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void firstCorrectionWithoutKeyAddsOnlyTheNewModelContribution() {
        service.adjustContribution(tenant, null, List.of(model), null, fact("13"));
        for (var period : time.resolve(time.now())) {
            assertAmount("53", cumulative("TENANT", period));
            assertAmount("53", cumulative("LLM-10", period));
        }
        assertEquals(6, committed.size());
        verify(periods, times(6)).updateById(any());
    }

    @ParameterizedTest
    @CsvSource({"CALCULATED,9,UNPRICED", "UNPRICED,,UNCALCULABLE", "UNCALCULABLE,,NOT_CHARGEABLE",
            "NOT_CHARGEABLE,,CALCULATED"})
    void replacingContributionTransfersExactlyOneClassification(String previousStatus, String amount,
            String nextStatus) {
        var row = emptyCumulative();
        row.setUsedCostFen(amount == null ? BigDecimal.ZERO : new BigDecimal(amount));
        row.setCalculatedCount("CALCULATED".equals(previousStatus) ? 1L : 0L);
        row.setUnpricedCount("UNPRICED".equals(previousStatus) ? 1L : 0L);
        row.setUncalculableCount("UNCALCULABLE".equals(previousStatus) ? 1L : 0L);
        row.setNotChargeableCount("NOT_CHARGEABLE".equals(previousStatus) ? 1L : 0L);
        var before = fact(amount);
        before.setCostStatus(previousStatus);
        var after = fact("CALCULATED".equals(nextStatus) ? "13" : null);
        after.setCostStatus(nextStatus);
        CostBudgetService.replace(row, before, after);
        assertAmount("CALCULATED".equals(nextStatus) ? "13" : "0", row);
        assertEquals("CALCULATED".equals(nextStatus) ? 1L : 0L, row.getCalculatedCount());
        assertEquals("UNPRICED".equals(nextStatus) ? 1L : 0L, row.getUnpricedCount());
        assertEquals("UNCALCULABLE".equals(nextStatus) ? 1L : 0L, row.getUncalculableCount());
        assertEquals("NOT_CHARGEABLE".equals(nextStatus) ? 1L : 0L, row.getNotChargeableCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"negative-amount", "negative-count", "overflow", "precision", "scale"})
    void invalidContributionCannotBecomeValidBudget(String scenario) {
        var row = emptyCumulative();
        var before = fact("1");
        var after = fact("1");
        switch (scenario) {
            case "negative-count" -> { before.setCostStatus("UNPRICED"); }
            case "overflow" -> { row.setCalculatedCount(Long.MAX_VALUE); before = null; }
            case "precision" -> { after.setEstimatedCostFen(new BigDecimal("1E+47")); before = null; }
            case "scale" -> { after.setEstimatedCostFen(new BigDecimal("0.0000000000000000001")); before = null; }
            case "negative-amount" -> { }
            default -> throw new IllegalArgumentException("unknown scenario");
        }
        var previous = before;
        Class<? extends RuntimeException> expected = "overflow".equals(scenario)
                ? ArithmeticException.class : IllegalStateException.class;
        assertThrows(expected, () -> CostBudgetService.replace(row, previous, after));
    }

    @Test
    void staleReplayCannotApplyOriginalFees() {
        var row = prepareApplication("CALCULATED", false);
        service.applyReconciliationReplay("event", 9L, 3L);
        assertFalse(row.getBudgetApplied());
        verify(tenants, never()).apply(any(), any());
        verify(models, never()).apply(any(), any());
        verify(settlements, never()).updateById(any());
    }

    @Test
    void alreadyCorrectedLateOriginalIsRecordedWithoutChargingTwice() {
        var row = prepareApplication("CALCULATED", false);
        var invocation = invocations.lockRequest("request");
        when(tracking.acceptsReplay(invocation, 9L, 3L)).thenReturn(true);
        when(tracking.hasAdjustment(invocation)).thenReturn(true);

        service.applyReconciliationReplay("event", 9L, 3L);

        assertTrue(row.getBudgetApplied());
        assertEquals(time.local(time.now()), row.getAppliedAt());
        assertEquals("SETTLED", invocation.getState());
        verify(tracking).reviewLateConflict(invocation, row);
        verify(tenants, never()).apply(any(), any());
        verify(models, never()).apply(any(), any());
        verify(periods, never()).updateById(any());
        assertTrue(committed.isEmpty());
    }

    @Test
    void currentReplayAppliesOnceAndUnknownEvidenceKeepsInvocationUnknown() {
        var row = prepareApplication("UNCALCULABLE", false);
        var invocation = invocations.lockRequest("request");
        when(tracking.acceptsReplay(invocation, 9L, 3L)).thenReturn(true);
        when(tracking.activeRequest(invocation)).thenReturn(true);
        service.applyReconciliationReplay("event", 9L, 3L);
        service.applyReconciliationReplay("event", 9L, 3L);
        assertTrue(row.getBudgetApplied());
        assertEquals("UNKNOWN", invocation.getState());
        assertEquals("UPSTREAM_RESULT_UNKNOWN", invocation.getLastErrorCode());
        verify(tenants).apply(tenant, row);
        verify(models).apply(model, row);
        verify(settlements).updateById(row);
    }

    @Test
    void alertDependencyFailureDoesNotUndoOriginalApplicationAndIsRetriedAfterIt() {
        var row = prepareApplication("CALCULATED", false);
        var invocation = invocations.lockRequest("request");
        doThrow(new IllegalStateException("alert unavailable")).when(tenants).capture(eq(tenant), any(), any());
        service.apply("event");
        assertTrue(row.getBudgetApplied());
        assertEquals("SETTLED", invocation.getState());
        verify(tenants).apply(tenant, row);
        verify(tenants, times(2)).capture(eq(tenant), any(), any());
        verify(settlements).updateById(row);
    }

    @Test
    void confirmedAlertReadinessAndHistoricalPeriodsHaveExplicitRetryResults() {
        assertTrue(service.captureConfirmedAlerts(TENANT_ID, MODEL_ID, time.local(time.now()).minusYears(1)));
        verify(read, never()).staged(any(), any());
        when(read.staged(eq(TENANT_ID), any())).thenReturn(CostReconciliationBudgetRead.StagedCosts.UNAVAILABLE);
        assertFalse(service.captureConfirmedAlerts(TENANT_ID, MODEL_ID, time.local(time.now())));
        verify(tenants, never()).capture(eq(tenant), any(), any());
        when(read.staged(eq(TENANT_ID), any())).thenReturn(CostReconciliationBudgetRead.StagedCosts.EMPTY);
        assertTrue(service.captureConfirmedAlerts(TENANT_ID, MODEL_ID, time.local(time.now())));
        verify(tenants).capture(eq(tenant), eq(time.now()), any());
        verify(models).capture(eq(model), eq(time.now()), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "LLM", "API_KEY"})
    void enabledScopeIsEnforcedIndependentlyAndMissingLimitRemainsUnlimited(String scope) {
        tenant.setCostLimitEnabled("TENANT".equals(scope));
        model.setCostLimitEnabled("LLM".equals(scope));
        key.setCostLimitEnabled("API_KEY".equals(scope));
        var failure = assertThrows(CostLimitExceededException.class, () -> service.check(actor, MODEL_ID));
        assertEquals(3, failure.getStatus().limits().size());
        assertTrue(failure.getStatus().limits().stream().allMatch(hit -> scope.equals(hit.scope())));
        tenant.setDailyLimitFen(null);
        tenant.setWeeklyLimitFen(null);
        tenant.setMonthlyLimitFen(null);
        model.setDailyLimitFen(null);
        model.setWeeklyLimitFen(null);
        model.setMonthlyLimitFen(null);
        key.setCostLimitDailyFen(null);
        key.setCostLimitWeeklyFen(null);
        key.setCostLimitMonthlyFen(null);
        service.check(actor, MODEL_ID);
        verify(periods, never()).updateById(any());
    }

    @Test
    void accountStatusIncludesStagingButHidesNumbersWhenFactsAreUnavailable() {
        when(keys.lockOwnedBudgetKey(TENANT_ID, KEY_ID, 42L)).thenReturn(key);
        var result = service.status(TENANT_ID, KEY_ID, 42L);
        assertEquals("LIMITED", result.budgetState());
        assertEquals(3, result.limits().size());
        assertTrue(result.periods().stream().allMatch(period -> "100".equals(period.usedCostFen())
                && "2".equals(period.calculatedCount()) && "0".equals(period.remainingCostFen())));
        assertTrue(committed.values().stream().allMatch(row -> row.getUsedCostFen().equals(new BigDecimal("40"))));
        when(read.staged(eq(TENANT_ID), any())).thenReturn(CostReconciliationBudgetRead.StagedCosts.UNAVAILABLE);
        var unavailable = service.status(TENANT_ID, KEY_ID, 42L);
        assertEquals("UNKNOWN", unavailable.budgetState());
        assertTrue(unavailable.periods().stream().allMatch(period -> period.usedCostFen() == null
                && period.calculatedCount() == null && period.exceeded() == null));
    }

    @Test
    void missingPeriodsRebuildFromEffectiveFactsAndExplicitRebuildDoesNotAppendStaging() {
        when(keys.lockOwnedBudgetKey(TENANT_ID, KEY_ID, 42L)).thenReturn(key);
        when(periods.lockPeriod(eq(TENANT_ID), eq(KEY_ID), anyString(), any())).thenReturn(null);
        doAnswer(call -> {
            var row = call.getArgument(0, CostBudgetPeriodDO.class);
            row.setUsedCostFen(new BigDecimal("25"));
            row.setCalculatedCount(2L);
            return null;
        }).when(read).rebuild(any(), eq(KEY_ID), isNull());
        var result = service.status(TENANT_ID, KEY_ID, 42L);
        assertEquals("AVAILABLE", result.budgetState());
        assertTrue(result.periods().stream().allMatch(period -> "85".equals(period.usedCostFen())));
        var inserted = ArgumentCaptor.forClass(CostBudgetPeriodDO.class);
        verify(periods, times(3)).insert(inserted.capture());
        assertTrue(inserted.getAllValues().stream().allMatch(row -> row.getUsedCostFen().equals(new BigDecimal("25"))));

        clearInvocations(periods);
        service.rebuild(TENANT_ID, KEY_ID);
        var rebuilt = ArgumentCaptor.forClass(CostBudgetPeriodDO.class);
        verify(periods, times(3)).insert(rebuilt.capture());
        assertTrue(rebuilt.getAllValues().stream().allMatch(row -> row.getUsedCostFen().equals(new BigDecimal("25"))
                && Long.valueOf(2L).equals(row.getCalculatedCount())));
        verify(settlements, never()).history(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant-only", "model-only", "none"})
    void confirmedAlertsOnlyEvaluateReadyEnabledScopes(String scope) {
        tenant.setCostLimitEnabled("tenant-only".equals(scope));
        model.setCostLimitEnabled("model-only".equals(scope));
        assertTrue(service.captureConfirmedAlerts(TENANT_ID, MODEL_ID, time.local(time.now())));
        verify(tenants, times("tenant-only".equals(scope) ? 1 : 0)).capture(eq(tenant), any(), any());
        verify(models, times("model-only".equals(scope) ? 1 : 0)).capture(eq(model), any(), any());
        if ("none".equals(scope)) {
            verify(read, never()).staged(any(), any());
        }
    }

    @ParameterizedTest
    @CsvSource({"REJECTED,true,true", "REJECTED,false,false", "NOT_SENT,true,false",
            "UNKNOWN,true,false", "COMPLETED,true,false"})
    void ambiguousRetryIsBlockedOnlyAfterReconciliationActivation(String outcome, boolean enabled,
            boolean blocked) {
        when(tracking.enabled()).thenReturn(enabled);
        assertEquals(blocked, service.blocksAmbiguousRetry(UpstreamExecutionOutcome.valueOf(outcome)));
        service.setReconciliationTracking(null);
        assertFalse(service.blocksAmbiguousRetry(UpstreamExecutionOutcome.valueOf(outcome)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"corrected", "automatic", "conflict"})
    void changedTerminalSnapshotPreservesOriginalAndRequiresRecognizedEvidence(String reason) {
        var original = prepareApplication("CALCULATED", true);
        original.setPayloadHash("original-snapshot");
        var invocation = invocations.lockRequest("request");
        invocation.setEventId("event");
        when(tracking.retainCorrectedConflict(invocation)).thenReturn("corrected".equals(reason));
        when(tracking.retainsVerifiedAutomaticEvidence(invocation, original)).thenReturn("automatic".equals(reason));
        var event = new TokenUsageEvent();
        event.setTenantId(TENANT_ID);
        event.setUserId(42L);
        event.setRequestId("request");
        event.setServiceId(MODEL_ID);
        event.setOperation(ModelOperation.CHAT_COMPLETIONS.name());
        event.setSource("PLAYGROUND");
        event.setRequestStartedAt(time.now());
        event.setCostResult(new CostResult(CostStatus.CALCULATED, new BigDecimal("14")));

        service.settle(event);

        assertEquals(0, new BigDecimal("13").compareTo(original.getEstimatedCostFen()));
        assertTrue(original.getBudgetApplied());
        verify(settlements, never()).insert(any(CostBudgetSettlementDO.class));
        verify(tenants, never()).apply(any(), any());
        if ("conflict".equals(reason)) {
            assertEquals("UNKNOWN", invocation.getState());
            assertEquals("SNAPSHOT_CONFLICT", invocation.getLastErrorCode());
        } else {
            verify(registry).remove("request");
            assertNull(invocation.getLastErrorCode());
        }
    }

    private CostBudgetSettlementDO prepareApplication(String status, boolean applied) {
        actor.setApiKeyId(null);
        var row = fact("CALCULATED".equals(status) ? "13" : null);
        row.setApiKeyId(null);
        row.setUserId(42L);
        row.setRequestId("request");
        row.setEventId("event");
        row.setCostStatus(status);
        row.setBudgetApplied(applied);
        row.setOrigin("RECONCILIATION");
        var event = new TokenUsageEvent();
        event.setExecutionOutcome(UpstreamExecutionOutcome.COMPLETED);
        row.setEventPayloadJson(JSON.toJSONString(event));
        var invocation = new CostBudgetInvocationDO();
        invocation.setState("UNKNOWN");
        invocation.setRequestId("request");
        when(settlements.selectOne(any())).thenReturn(row);
        when(settlements.lockEvent("event")).thenReturn(row);
        when(invocations.lockRequest("request")).thenReturn(invocation);
        when(models.lockForRequest(TENANT_ID, "request", MODEL_ID)).thenReturn(List.of(model));
        return row;
    }

    private CostBudgetSettlementDO fact(String amount) {
        var row = new CostBudgetSettlementDO();
        row.setTenantId(TENANT_ID);
        row.setApiKeyId(KEY_ID);
        row.setServiceId(MODEL_ID);
        row.setRequestStartedAt(time.local(time.now()));
        row.setEstimatedCostFen(amount == null ? null : new BigDecimal(amount));
        row.setCostStatus("CALCULATED");
        return row;
    }

    private CostBudgetPeriodDO cumulative(String scope, BudgetPeriodResolver.Period period) {
        return committed.computeIfAbsent(scope + "-" + period.type(), ignored -> {
            var row = emptyCumulative();
            row.setPeriodType(period.type());
            row.setPeriodStart(period.start());
            row.setPeriodEnd(period.end());
            row.setUsedCostFen(new BigDecimal("40"));
            row.setCalculatedCount(1L);
            return row;
        });
    }

    private CostBudgetPeriodDO emptyCumulative() {
        var row = new CostBudgetPeriodDO();
        row.setUsedCostFen(BigDecimal.ZERO);
        row.setCalculatedCount(0L);
        row.setUnpricedCount(0L);
        row.setUncalculableCount(0L);
        row.setNotChargeableCount(0L);
        return row;
    }

    private void assertAmount(String expected, CostBudgetPeriodDO row) {
        assertEquals(0, new BigDecimal(expected).compareTo(row.getUsedCostFen()));
    }
}
