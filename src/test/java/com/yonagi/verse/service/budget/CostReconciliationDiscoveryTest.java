package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 断言核账业务状态、金额保护和首次归属，不模拟数据库并发保证。 */
class CostReconciliationDiscoveryTest {
    static Stream<Consumer<CostBudgetInvocationDO>> mismatchedHistoricalIdentity() {
        return Stream.of(i->i.setTenantId(8L),i->i.setEventId("foreign"),i->i.setOriginalUserId(99L),
                i->i.setApiKeyId(99L),i->i.setSource("API_KEY"),i->i.setRequestStartedAt(LocalDateTime.MIN),
                i->i.setReconciliationGeneration(null),i->i.setReconciliationGeneration(0L),
                i->i.setReconciliationDisposition("CLEAR"));
    }

    @ParameterizedTest
    @MethodSource("mismatchedHistoricalIdentity")
    void expiryRejectsEveryChangedOriginalIdentityWithoutTransferring(Consumer<CostBudgetInvocationDO> change) {
        var row=expiryFixture();change.accept(invocation);
        assertThrows(CostBudgetSnapshot.ConflictException.class,()->service.expireAutomatic(page,row,0));
        assertEquals("AUTO",row.getState());assertEquals(0L,row.getVersion());verifyNoInteractions(signals);
    }
    private final TenantCostBudgetLedger tenants = mock(TenantCostBudgetLedger.class);
    private final CostBudgetInvocationMapper invocations = mock(CostBudgetInvocationMapper.class);
    private final CostBudgetSettlementMapper settlements = mock(CostBudgetSettlementMapper.class);
    private final CostReconciliationAttemptMapper attempts = mock(CostReconciliationAttemptMapper.class);
    private final CostReconciliationPageBarrier barrier = mock(CostReconciliationPageBarrier.class);
    private final CostReconciliationSignals signals = mock(CostReconciliationSignals.class);
    private final CostReconciliationScanMapper scans = mock(CostReconciliationScanMapper.class);
    private final CostReconciliationBatchMapper batches = mock(CostReconciliationBatchMapper.class);
    private final CostReconciliationCaseMapper cases = mock(CostReconciliationCaseMapper.class);
    private final ReliableDomainEventPublisher events = mock(ReliableDomainEventPublisher.class);
    private final CostReconciliationTracking tracking = mock(CostReconciliationTracking.class);
    private final CostReconciliationDiscovery service = new CostReconciliationDiscovery(mock(CostBudgetService.class),
            tenants, mock(LlmCostBudgetLedger.class), invocations, settlements, attempts, barrier, signals,
            scans, batches, cases, new ReconciliationEvidence(new CostCalculator()), events, tracking);
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 14, 8, 0);
    private CostBudgetInvocationDO invocation;
    private CostReconciliationTaskEvent page;

    @BeforeEach
    void setup() {
        invocation = new CostBudgetInvocationDO();
        invocation.setRequestId("request"); invocation.setEventId("event"); invocation.setTenantId(7L);
        invocation.setOriginalUserId(9L); invocation.setSource("PLAYGROUND"); invocation.setRequestStartedAt(now.minusDays(1));
        invocation.setReconciliationGeneration(2L); invocation.setReconciliationDisposition("TRACKING");
        invocation.setState("RUNNING"); invocation.setLeaseUntil(now.minusMinutes(2));
        page = new CostReconciliationTaskEvent();
        page.setTaskId("page"); page.setRunId(3L); page.setTenantId(7L); page.setGeneration(2L);
        when(barrier.admit(page)).thenReturn(true);
        when(invocations.lockRequest("request")).thenReturn(invocation);
        when(scans.databaseTime()).thenReturn(now);
        doAnswer(call -> { call.getArgument(0, CostReconciliationBatchDO.class).setId(20L); return 1; })
                .when(batches).insert(any(CostReconciliationBatchDO.class));
        doAnswer(call -> { call.getArgument(0, CostReconciliationCaseDO.class).setId(30L); return 1; })
                .when(cases).insert(any(CostReconciliationCaseDO.class));
    }

    @Test
    void finalizingUnappliedFactCreatesAutoAndPersistsCaseDisposition() {
        invocation.setState("FINALIZING");
        var original = settlement("CALCULATED", false);
        when(settlements.lockEvent("event")).thenReturn(original);
        assertTrue(service.classify(page, invocation, false));
        assertEquals("FINALIZING", invocation.getState());
        assertEquals("CASE", invocation.getReconciliationDisposition());
        var saved = ArgumentCaptor.forClass(CostReconciliationCaseDO.class);
        verify(cases).insert(saved.capture());
        assertEquals(20L, saved.getValue().getBatchId());
        assertEquals(9L, saved.getValue().getUserId());
        assertEquals("AUTO", saved.getValue().getState());
        assertEquals(now.plusMinutes(5), saved.getValue().getAutomaticDeadlineAt());
        var event = ArgumentCaptor.forClass(CostReconciliationEvent.class);
        verify(events).publish(event.capture(), eq(7L));
        assertEquals("event", event.getValue().getStagedEventId());
        assertEquals(new BigDecimal("123"), original.getEstimatedCostFen());
    }

    @Test
    void activeAndIntentGraceRemainTrackingWithoutNewCase() {
        service.classify(page, invocation, true);
        assertEquals("TRACKING", invocation.getReconciliationDisposition());
        assertEquals(now.plusSeconds(30), invocation.getLeaseUntil());
        invocation.setLeaseUntil(now.minusSeconds(10));
        var attempt = attempt(); attempt.setOutcome("INTENT");
        when(attempts.latest("request")).thenReturn(attempt);
        service.classify(page, invocation, false);
        assertEquals("TRACKING", invocation.getReconciliationDisposition());
        verify(cases, never()).insert(any(CostReconciliationCaseDO.class));
        verifyNoInteractions(events);
    }

    @Test
    void absenceOfSendIntentRevokesPermissionAndKnownAppliedClearsTracking() {
        service.classify(page, invocation, false);
        assertEquals("NO_UPSTREAM", invocation.getState());
        assertEquals("CLEAR", invocation.getReconciliationDisposition());
        invocation.setState("SETTLED"); invocation.setReconciliationDisposition("TRACKING");
        when(settlements.lockEvent("event")).thenReturn(settlement("CALCULATED", true));
        service.classify(page, invocation, false);
        assertEquals("CLEAR", invocation.getReconciliationDisposition());
        verifyNoInteractions(events);
    }

    @Test
    void unknownTokenUsageBecomesPendingAndKeepsOriginalIdentity() {
        when(attempts.latest("request")).thenReturn(attempt());
        service.classify(page, invocation, false);
        var saved = ArgumentCaptor.forClass(CostReconciliationCaseDO.class);
        verify(cases).insert(saved.capture());
        assertEquals("PENDING", saved.getValue().getState());
        assertEquals("USAGE_INCOMPLETE", saved.getValue().getReason());
        assertEquals(invocation.getRequestStartedAt(), saved.getValue().getRequestStartedAt());
        assertEquals("CASE", invocation.getReconciliationDisposition());
        assertEquals("UNKNOWN", invocation.getState());
        verifyNoInteractions(events);
    }

    @Test
    void duplicateCaseMaintainsFirstBatchAndDoesNotRepublishAutomaticEvent() {
        var existing = automatic(); existing.setBatchId(123L);
        when(cases.lockRequest(7L, "request")).thenReturn(existing);
        when(attempts.latest("request")).thenReturn(attempt());
        service.classify(page, invocation, false);
        assertEquals(123L, existing.getBatchId());
        assertEquals("CASE", invocation.getReconciliationDisposition());
        verify(cases, never()).insert(any(CostReconciliationCaseDO.class));
        verifyNoInteractions(events);
    }

    @Test
    void terminalPageAndCaseDispositionDoNotReclassify() {
        when(barrier.admit(page)).thenReturn(false);
        assertFalse(service.classify(page, invocation, false));
        verifyNoInteractions(tenants);
        when(barrier.admit(page)).thenReturn(true);
        invocation.setReconciliationDisposition("CASE");
        assertTrue(service.classify(page, invocation, false));
        verify(cases, never()).insert(any(CostReconciliationCaseDO.class));
    }

    @Test
    void mismatchedTenantAndChangedIdentityFailBeforeCaseMutation() {
        page.setTenantId(8L);
        assertThrows(IllegalStateException.class, () -> service.classify(page, invocation, false));
        page.setTenantId(7L); invocation.setReconciliationGeneration(1L);
        assertThrows(IllegalStateException.class, () -> service.classify(page, invocation, false));
        assertEquals("TRACKING", invocation.getReconciliationDisposition());
        verify(cases, never()).insert(any(CostReconciliationCaseDO.class));
    }

    @Test
    void dependencyFailureDoesNotMarkRequestComplete() {
        doThrow(new CostBudgetUnavailableException()).when(tenants).lock(7L);
        assertThrows(CostBudgetUnavailableException.class, () -> service.classify(page, invocation, false));
        assertEquals("TRACKING", invocation.getReconciliationDisposition());
        verifyNoInteractions(events);
    }

    @Test
    void caseOwnedAutoExpiryStillTransfersOriginalBatchWhenUsageUnknown() {
        var row = expiryFixture();
        when(attempts.selectById(10L)).thenReturn(attempt());
        assertTrue(service.expireAutomatic(page, row, 0));
        assertEquals("PENDING", row.getState());
        assertEquals(1L, row.getVersion());
        assertEquals(123L, row.getBatchId());
        verify(signals).batchChanged(row);
        service.expireAutomatic(page, row, 0);
        assertEquals(1L, row.getVersion());
    }

    @Test
    void confirmedUnappliedAmountCannotBecomeManuallyOverwritable() {
        var row = expiryFixture();
        when(attempts.selectById(10L)).thenReturn(attempt());
        var original = settlement("CALCULATED", false);
        when(settlements.lockEvent("event")).thenReturn(original);
        service.expireAutomatic(page, row, 0);
        assertEquals("REVIEW", row.getState());
        assertEquals(new BigDecimal("123"), original.getEstimatedCostFen());
        assertFalse(original.getBudgetApplied());
    }

    @Test
    void staleVersionOrFutureDeadlineSkipsButHistoricalPositiveGenerationIsAccepted() {
        var row = expiryFixture();
        row.setAutomaticDeadlineAt(now.plusMinutes(1));
        service.expireAutomatic(page, row, 0);
        assertEquals("AUTO", row.getState());
        row.setAutomaticDeadlineAt(now.minusMinutes(1));
        service.expireAutomatic(page, row, 2);
        assertEquals("AUTO", row.getState());
        invocation.setReconciliationGeneration(1L);
        service.expireAutomatic(page, row, 0);
        assertEquals("REVIEW", row.getState());
    }

    @Test
    void replayTransferUsesOriginalBatchAndLateProofKeepsAutomaticProtection() {
        var row = automatic();
        when(cases.lockInBatch(123L, 30L)).thenReturn(row);
        when(settlements.lockEvent("event")).thenReturn(settlement("UNCALCULABLE", true));
        service.transferAfterReplay(row, 0);
        assertEquals("PENDING", row.getState());
        verify(signals).batchChanged(row);
        row.setState("AUTO"); row.setVersion(2L);
        when(settlements.lockEvent("event")).thenReturn(settlement("CALCULATED", true));
        service.transferAfterReplay(row, 2);
        assertEquals("RESOLVED", row.getState());
        assertEquals(now, row.getResolvedAt());
    }

    @Test
    void requestPriceCreatesChargeabilityQuestionAndOriginalKeyIsRetained() {
        invocation.setApiKeyId(55L);invocation.setSource("API_KEY");
        var attempt=attempt();attempt.setPricingJson(JSON.toJSONString(new PricingSnapshot(1L,BillingMode.REQUEST,
                "CNY",null,null,null,null,null,new BigDecimal("13"),null,null)));
        when(attempts.latest("request")).thenReturn(attempt);
        service.classify(page,invocation,false);
        var saved=ArgumentCaptor.forClass(CostReconciliationCaseDO.class);verify(cases).insert(saved.capture());
        assertEquals("CHARGEABILITY_UNKNOWN",saved.getValue().getReason());
        assertEquals(new BigDecimal("13"),saved.getValue().getEstimatedCostFen());
        assertEquals(55L,saved.getValue().getApiKeyId());assertEquals("PENDING",saved.getValue().getState());
    }

    @Test
    void missingAutomaticIdentityFailsButClosedBarrierAndChangedCaseSkip() {
        var row=expiryFixture();when(barrier.admit(page)).thenReturn(false);
        assertFalse(service.expireAutomatic(page,row,0));assertEquals("AUTO",row.getState());
        when(barrier.admit(page)).thenReturn(true);
        assertThrows(IllegalStateException.class,()->service.expireAutomatic(page,null,0));
        row.setAutomaticDeadlineAt(null);service.expireAutomatic(page,row,0);
        assertEquals("AUTO",row.getState());
        row.setAutomaticDeadlineAt(now);when(invocations.lockRequest("request")).thenReturn(null);
        assertThrows(CostBudgetSnapshot.ConflictException.class,()->service.expireAutomatic(page,row,0));
    }

    @Test
    void replayRequiresAppliedOriginalAndLateCompleteUsageStaysAutomatic() {
        var row=automatic();when(cases.lockInBatch(123L,30L)).thenReturn(row);
        assertThrows(IllegalStateException.class,()->service.transferAfterReplay(row,0));
        when(settlements.lockEvent("event")).thenReturn(settlement("UNCALCULABLE",true));
        var attempt=attempt();attempt.setOutcome("COMPLETED");attempt.setUsageJson("{\"input\":100,\"cached\":0,\"output\":100}");
        when(attempts.selectById(10L)).thenReturn(attempt);
        service.transferAfterReplay(row,0);
        assertEquals("AUTO",row.getState());assertEquals(0L,row.getVersion());
        verify(tracking).queueLateAutomatic(invocation,row);
    }

    private CostReconciliationCaseDO expiryFixture() {
        var row = automatic();
        invocation.setReconciliationDisposition("CASE");
        when(cases.lockInBatch(123L, 30L)).thenReturn(row);
        var run = new CostReconciliationScanDO(); run.setCutoffAt(now);
        when(scans.selectById(3L)).thenReturn(run);
        return row;
    }

    private CostReconciliationCaseDO automatic() {
        var row = new CostReconciliationCaseDO();
        row.setId(30L); row.setBatchId(123L); row.setTenantId(7L); row.setUserId(9L);
        row.setRequestId("request"); row.setEventId("event"); row.setSource("PLAYGROUND");
        row.setRequestStartedAt(invocation.getRequestStartedAt()); row.setAttemptId(10L);
        row.setState("AUTO"); row.setVersion(0L); row.setAutomaticDeadlineAt(now.minusMinutes(1));
        return row;
    }

    private CostReconciliationAttemptDO attempt() {
        var attempt = new CostReconciliationAttemptDO();
        attempt.setId(10L); attempt.setRequestId("request"); attempt.setOutcome("UNKNOWN");
        attempt.setPricingJson(JSON.toJSONString(new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", null, null,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null, null)));
        return attempt;
    }

    private CostBudgetSettlementDO settlement(String status, boolean applied) {
        var row = new CostBudgetSettlementDO();
        row.setCostStatus(status); row.setBudgetApplied(applied); row.setEventId("event");
        row.setEstimatedCostFen(new BigDecimal("123"));
        return row;
    }
}
