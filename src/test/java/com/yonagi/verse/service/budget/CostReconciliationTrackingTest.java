package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostReconciliationAdjustmentDO;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAdjustmentMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAttemptMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.usage.UsageBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import org.springframework.test.util.ReflectionTestUtils;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.service.pricing.CostResult;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostReconciliationTrackingTest {
    private final CostReconciliationScanMapper scans = mock(CostReconciliationScanMapper.class);
    private final CostReconciliationAttemptMapper attempts = mock(CostReconciliationAttemptMapper.class);
    private final CostReconciliationCaseMapper cases = mock(CostReconciliationCaseMapper.class);
    private final CostReconciliationAdjustmentMapper adjustments = mock(CostReconciliationAdjustmentMapper.class);
    private final CostBudgetInvocationMapper invocations = mock(CostBudgetInvocationMapper.class);
    private final ReliableDomainEventPublisher events = mock(ReliableDomainEventPublisher.class);
    private final com.yonagi.verse.service.pricing.PricingResolver prices = mock(com.yonagi.verse.service.pricing.PricingResolver.class);
    private final CostReconciliationSignals signals = mock(CostReconciliationSignals.class);
    private final BudgetPeriodResolver time = new BudgetPeriodResolver(
            Clock.fixed(Instant.parse("2026-11-08T07:00:00Z"), ZoneOffset.UTC));
    private final CostReconciliationTracking tracking = new CostReconciliationTracking(scans, attempts, cases,
            adjustments, invocations, prices, new ReconciliationEvidence(new CostCalculator()), time, events, signals);
    private final CostReconciliationAttemptDO attempt = new CostReconciliationAttemptDO();
    private final CostReconciliationCaseDO row = new CostReconciliationCaseDO();

    @BeforeEach
    void setup() {
        var invocation = new CostBudgetInvocationDO();
        invocation.setRequestId("request");
        invocation.setTenantId(2L);
        invocation.setRequestStartedAt(time.local(time.now()).minusHours(1));
        when(invocations.lockRequest("request")).thenReturn(invocation);
        when(scans.activationGeneration()).thenReturn(1L);
        attempt.setId(31L);
        attempt.setRequestId("request");
        attempt.setServiceId(10L);
        attempt.setProvider("openai");
        attempt.setOperation("CHAT_COMPLETIONS");
        attempt.setOutcome("COMPLETED");
        attempt.setPricingJson(JSON.toJSONString(new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", null, null,
                new BigDecimal("200"), new BigDecimal("50"), new BigDecimal("800"), null, null, null)));
        when(attempts.selectById(31L)).thenReturn(attempt);
        row.setId(7L);
        row.setBatchId(8L);
        row.setUserId(9L);
        row.setTenantId(2L);
        row.setRequestId("request");
        row.setAttemptId(31L);
        row.setState("PENDING");
        row.setVersion(1L);
        when(cases.lockRequest(2L, "request")).thenReturn(row);
    }

    @Test
    void lateCompleteUsageAdvancesOriginalBatchAndDeduplicatesRepeatedEvidence() {
        tracking.completeAttempt(31L, "request", exactUsage(0L));
        tracking.completeAttempt(31L, "request", exactUsage(0L));

        assertEquals("AUTO", row.getState());
        assertEquals(2L, row.getVersion());
        assertEquals(8L, row.getBatchId());
        assertFalse(CostReconciliationCorrectionService.acceptsAutomatic(row, 0));
        assertTrue(CostReconciliationCorrectionService.acceptsAutomatic(row, 2));
        assertEquals(time.local(time.now()).plusMinutes(5), row.getAutomaticDeadlineAt());
        var published = ArgumentCaptor.forClass(CostReconciliationEvent.class);
        verify(events).publish(published.capture(), eq(2L));
        assertEquals(7L, published.getValue().getAutomaticCaseId());
        assertEquals(2L, published.getValue().getAutomaticCaseVersion());
    }

    @Test
    void lateUsageThatDisagreesWithManualResolutionFlagsReviewWithoutChangingIt() {
        row.setState("RESOLVED");
        row.setVersion(3L);
        var manual = new CostReconciliationAdjustmentDO();
        manual.setServiceId(10L);
        manual.setCostStatus("CALCULATED");
        manual.setCostFen(new BigDecimal("1000"));
        manual.setUsageJson("{\"input\":1000000,\"cached\":0,\"output\":1000000}");
        when(adjustments.latest(7L)).thenReturn(manual);

        tracking.completeAttempt(31L, "request", exactUsage(0L));
        assertNull(row.getReviewRequired());
        tracking.completeAttempt(31L, "request", exactUsage(1000L));

        assertEquals("RESOLVED", row.getState());
        assertTrue(row.getReviewRequired());
        assertEquals(4L, row.getVersion());
        assertEquals(new BigDecimal("1000"), manual.getCostFen());
        verify(adjustments, never()).insert(any(CostReconciliationAdjustmentDO.class));
        verifyNoInteractions(events);
    }


    @Test
    void registrationCapturesOriginalUserAndGenerationBeforeExistingInvocationWrite() {
        var invocation = invocation();
        tracking.register(invocation, 9L);
        assertEquals(9L, invocation.getOriginalUserId());
        assertEquals(1L, invocation.getReconciliationGeneration());
        assertEquals("TRACKING", invocation.getReconciliationDisposition());
        when(scans.activationGeneration()).thenReturn(0L);
        tracking.register(invocation, 9L);
        assertEquals("CLEAR", invocation.getReconciliationDisposition());
        ReflectionTestUtils.setField(tracking, "captureEnabled", true);
        tracking.register(invocation, 9L);
        assertEquals(0L, invocation.getReconciliationGeneration());
        assertEquals("TRACKING", invocation.getReconciliationDisposition());
        verify(invocations, never()).updateById(any(CostBudgetInvocationDO.class));
    }

    @Test
    void stagingAndApplicationPreserveCaseAndKeepOnlyPositiveUnknownRequestsTracking() {
        var invocation = invocation();
        invocation.setReconciliationDisposition("CLEAR");
        tracking.staged(invocation, new TokenUsageEvent());
        assertEquals("TRACKING", invocation.getReconciliationDisposition());
        tracking.applied(invocation, CostStatus.UNCALCULABLE);
        assertEquals("TRACKING", invocation.getReconciliationDisposition());
        tracking.applied(invocation, CostStatus.CALCULATED);
        assertEquals("CLEAR", invocation.getReconciliationDisposition());
        invocation.setReconciliationGeneration(0L);
        tracking.staged(invocation, new TokenUsageEvent());
        assertEquals("CLEAR", invocation.getReconciliationDisposition());
        tracking.applied(invocation, CostStatus.UNCALCULABLE);
        assertEquals("CLEAR", invocation.getReconciliationDisposition());
        invocation.setReconciliationGeneration(null);
        tracking.staged(invocation, new TokenUsageEvent());
        tracking.applied(invocation, CostStatus.UNCALCULABLE);
        assertEquals("CLEAR", invocation.getReconciliationDisposition());
        invocation.setReconciliationDisposition("CASE");
        tracking.staged(invocation, new TokenUsageEvent());
        tracking.applied(invocation, CostStatus.CALCULATED);
        assertEquals("CASE", invocation.getReconciliationDisposition());
        invocation.setReconciliationDisposition("TRACKING");
        tracking.abandoned(invocation);
        assertEquals("CLEAR", invocation.getReconciliationDisposition());
        tracking.applied(null, CostStatus.CALCULATED);
    }

    static Stream<Consumer<TokenUsageEvent>> wrongIdentity() {
        return Stream.of(e -> e.setTenantId(99L), e -> e.setUserId(99L), e -> e.setApiKeyId(99L),
                e -> e.setSource("API_KEY"), e -> e.setRequestStartedAt(null),
                e -> e.setRequestStartedAt(Instant.EPOCH));
    }

    @ParameterizedTest
    @MethodSource("wrongIdentity")
    void terminalRejectsEveryChangedOriginalIdentity(Consumer<TokenUsageEvent> change) {
        var event = terminal(); change.accept(event);
        assertThrows(CostBudgetSnapshot.ConflictException.class, () -> tracking.prepareTerminal(event, invocation(), null));
        assertNull(event.getCostResult());
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-04T08:06:55.077901Z,2026-10-04T08:06:55.077000Z,true",
            "2026-10-04T08:19:45.063000Z,2026-10-04T08:19:45.063890Z,true",
            "2026-10-04T08:20:00.123001Z,2026-10-04T08:20:00.123999Z,true",
            "2026-10-04T15:59:59.999000Z,2026-10-04T15:59:59.999999Z,true",
            "2026-10-04T08:06:55.077999Z,2026-10-04T08:06:55.078000Z,false",
            "2026-10-04T15:59:59.999999Z,2026-10-04T16:00:00.000000Z,false",
            "2026-11-15T15:59:59.999999Z,2026-11-15T16:00:00.000000Z,false",
            "2026-10-31T15:59:59.999999Z,2026-10-31T16:00:00.000000Z,false"
    })
    void originalTimeIdentityUsesSameMillisecondWithoutRewritingFacts(
            String invocationTime, String eventTime, boolean sameMillisecond) {
        Instant original=Instant.parse(invocationTime);
        Instant terminalTime=Instant.parse(eventTime);
        var invocation=invocation();invocation.setRequestStartedAt(time.local(original));
        var event=terminal();event.setRequestStartedAt(terminalTime);
        if(sameMillisecond) {
            tracking.prepareTerminal(event,invocation,null);
            assertEquals(CostStatus.UNCALCULABLE,event.getCostResult().status());
            assertEquals(time.resolve(original),time.resolve(terminalTime));
        } else {
            assertThrows(CostBudgetSnapshot.ConflictException.class,
                    ()->tracking.prepareTerminal(event,invocation,null));
            assertNull(event.getCostResult());
        }
        assertEquals(time.local(original),invocation.getRequestStartedAt());
        assertEquals(terminalTime,event.getRequestStartedAt());
    }

    @Test
    void terminalKeepsUnknownAsUnknownAndOriginalSnapshotPriceAndAmountImmutable() {
        var invocation = invocation(); var event = terminal();
        tracking.prepareTerminal(event, invocation, null);
        assertEquals(CostStatus.UNCALCULABLE, event.getCostResult().status());
        var original = new CostBudgetSettlementDO();
        var stored = terminal(); stored.setCostResult(new CostResult(CostStatus.CALCULATED,new BigDecimal("123")));
        original.setEventPayloadJson(JSON.toJSONString(stored));
        tracking.prepareTerminal(event, invocation, original);
        assertEquals(new BigDecimal("123"), event.getCostResult().estimatedCostFen());
        invocation.setReconciliationGeneration(0L);
        event.setCostResult(CostResult.of(CostStatus.NOT_CHARGEABLE));
        tracking.prepareTerminal(event, invocation, null);
        assertEquals(CostStatus.NOT_CHARGEABLE,event.getCostResult().status());
    }

    @Test
    void notSentTerminalRepairsMissingRejectionEvidenceBeforeCalculatingCost() {
        fixedPriceIntent();
        TokenUsageEvent event = attemptedTerminal();
        event.setExecutionOutcome(UpstreamExecutionOutcome.NOT_SENT);
        event.setStatus("FAIL");

        tracking.prepareTerminal(event, invocation(), null);

        assertEquals("NOT_SENT", attempt.getOutcome());
        assertEquals(CostStatus.NOT_CHARGEABLE, event.getCostResult().status());
    }

    @Test
    void verifiedCompletionSurvivesAbortedDeliveryAndUsesHistoricalPrice() {
        fixedPriceIntent();
        TokenUsageEvent event = attemptedTerminal();
        UpstreamCompletionProof proof = UpstreamCompletionProof.capture(31L, "request", 10L,
                ModelOperation.CHAT_COMPLETIONS, null);

        tracking.prepareTerminal(event, invocation(), null, proof);
        tracking.prepareTerminal(event, invocation(), null, proof);

        assertEquals("COMPLETED", attempt.getOutcome());
        assertEquals("ABORTED", event.getStatus());
        assertEquals(CostStatus.CALCULATED, event.getCostResult().status());
        assertEquals(new BigDecimal("600"), event.getCostResult().estimatedCostFen());
        verifyNoInteractions(prices);
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt", "request", "service", "operation", "eventRequest", "attemptRequest",
            "eventService", "eventOperation", "outcome", "missingAttempt", "notSentAttempt"})
    void completionProofMustMatchTheOriginalPhysicalAttempt(String mismatch) {
        fixedPriceIntent();
        TokenUsageEvent event = attemptedTerminal();
        UpstreamCompletionProof proof = UpstreamCompletionProof.capture("attempt".equals(mismatch) ? 99L : 31L,
                "request".equals(mismatch) ? "another-request" : "request",
                "service".equals(mismatch) ? 99L : 10L,
                "operation".equals(mismatch) ? ModelOperation.RESPONSES : ModelOperation.CHAT_COMPLETIONS, null);
        switch (mismatch) {
            case "eventRequest" -> event.setRequestId("another-request");
            case "attemptRequest" -> attempt.setRequestId("another-request");
            case "eventService" -> event.setServiceId(99L);
            case "eventOperation" -> event.setOperation("RESPONSES");
            case "outcome" -> event.setExecutionOutcome(UpstreamExecutionOutcome.UNKNOWN);
            case "missingAttempt" -> when(attempts.latest("request")).thenReturn(null);
            case "notSentAttempt" -> attempt.setOutcome("NOT_SENT");
            default -> { }
        }

        assertThrows(CostBudgetSnapshot.ConflictException.class,
                () -> tracking.prepareTerminal(event, invocation(), null, proof));
        assertNull(event.getCostResult());
        assertNotEquals("COMPLETED", attempt.getOutcome());
    }

    @Test
    void unverifiedProviderCannotConfirmFixedCostAndShadowModeKeepsOriginalCost() {
        fixedPriceIntent();
        TokenUsageEvent event = attemptedTerminal();
        UpstreamCompletionProof proof = UpstreamCompletionProof.capture(31L, "request", 10L,
                ModelOperation.CHAT_COMPLETIONS, null);
        attempt.setProvider("custom");
        tracking.prepareTerminal(event, invocation(), null, proof);
        assertEquals(CostStatus.UNCALCULABLE, event.getCostResult().status());
        assertEquals("INTENT", attempt.getOutcome());

        attempt.setProvider("openai");
        CostBudgetInvocationDO shadow = invocation();
        shadow.setReconciliationGeneration(0L);
        event.setCostResult(CostResult.of(CostStatus.NOT_CHARGEABLE));
        tracking.prepareTerminal(event, shadow, null, proof);
        assertEquals(CostStatus.NOT_CHARGEABLE, event.getCostResult().status());
        assertEquals("COMPLETED", attempt.getOutcome());
    }

    @Test
    void completionFreezesMutableUsageAndDoesNotRepriceAnExistingSettlement() {
        fixedPriceIntent();
        UsageBreakdown usage = exactUsage(0L);
        UpstreamCompletionProof proof = UpstreamCompletionProof.capture(31L, "request", 10L,
                ModelOperation.CHAT_COMPLETIONS, usage);
        usage.rawUsage().put("cached_tokens", 99);
        assertEquals(0L, proof.usage().rawUsage().getLong("cached_tokens"));
        proof.usage().rawUsage().put("cached_tokens", 123);
        assertEquals(0L, proof.usage().rawUsage().getLong("cached_tokens"));

        TokenUsageEvent event = attemptedTerminal();
        CostBudgetSettlementDO original = new CostBudgetSettlementDO();
        TokenUsageEvent stored = attemptedTerminal();
        stored.setCostResult(new CostResult(CostStatus.CALCULATED, new BigDecimal("123")));
        original.setEventPayloadJson(JSON.toJSONString(stored));
        tracking.prepareTerminal(event, invocation(), original, proof);
        assertEquals("COMPLETED", attempt.getOutcome());
        assertEquals(new BigDecimal("123"), event.getCostResult().estimatedCostFen());
    }

    private void fixedPriceIntent() {
        attempt.setOutcome("INTENT");
        attempt.setPricingJson(JSON.toJSONString(new PricingSnapshot(1L, BillingMode.REQUEST, "CNY", null, null,
                null, null, null, new BigDecimal("600"), null, null)));
        when(attempts.latest("request")).thenReturn(attempt);
    }

    private TokenUsageEvent attemptedTerminal() {
        TokenUsageEvent event = terminal();
        event.setServiceId(10L);
        event.setOperation("CHAT_COMPLETIONS");
        event.setStatus("ABORTED");
        event.setExecutionOutcome(UpstreamExecutionOutcome.COMPLETED);
        return event;
    }

    @Test
    void missingPositiveIdentityFailsClosedWhileUncapturedLegacyRemainsCompatible() {
        var invocation = invocation(); invocation.setOriginalUserId(null);
        assertThrows(CostBudgetSnapshot.ConflictException.class, () -> tracking.prepareTerminal(terminal(),invocation,null));
        invocation.setReconciliationGeneration(0L);
        var event = terminal(); tracking.prepareTerminal(event,invocation,null);
        assertNull(event.getCostResult());
        when(cases.selectOne(any())).thenReturn(row);
        assertThrows(CostBudgetSnapshot.ConflictException.class, () -> tracking.prepareTerminal(terminal(),invocation,null));
    }

    @Test
    void knownOriginalResolvesOriginalBatchAndPositiveHistoricalGenerationStaysActive() {
        var invocation = invocation(); when(cases.selectOne(any())).thenReturn(row);
        tracking.resolveFromOriginal(invocation,CostStatus.CALCULATED);
        assertEquals("RESOLVED",row.getState()); assertEquals(2L,row.getVersion());
        assertEquals(8L,row.getBatchId()); verify(signals).batchChanged(row);
        assertTrue(tracking.activeRequest(invocation));
        invocation.setReconciliationGeneration(null);
        assertTrue(tracking.activeRequest(invocation));
        when(cases.selectOne(any())).thenReturn(null);
        assertFalse(tracking.activeRequest(invocation));
        invocation.setReconciliationGeneration(0L);
        assertFalse(tracking.activeRequest(invocation));
        when(scans.activationGeneration()).thenReturn(0L);
        assertFalse(tracking.activeRequest(invocation)); assertFalse(tracking.activeRequest(null));
    }

    private CostBudgetInvocationDO invocation() {
        var invocation = new CostBudgetInvocationDO(); invocation.setRequestId("request");invocation.setTenantId(2L);
        invocation.setSource("PLAYGROUND");invocation.setOriginalUserId(9L);invocation.setReconciliationGeneration(1L);
        invocation.setRequestStartedAt(time.local(time.now()));invocation.setReconciliationDisposition("TRACKING");
        return invocation;
    }

    private TokenUsageEvent terminal() {
        var event = new TokenUsageEvent();event.setRequestId("request");event.setTenantId(2L);event.setUserId(9L);
        event.setSource("PLAYGROUND");event.setRequestStartedAt(time.now());return event;
    }

    @Test
    void sendIntentUsesOriginalInvocationPermissionAndPersistsHistoricalPrice() {
        var invocation=invocation();invocation.setState("UNKNOWN");
        when(invocations.selectOne(any())).thenReturn(invocation);
        when(invocations.lockRequest("request")).thenReturn(invocation);
        assertThrows(CostBudgetUnavailableException.class,()->tracking.intent(2L,"request",10L,"openai",
                com.yonagi.verse.common.enums.ModelOperation.CHAT_COMPLETIONS));
        invocation.setState("RUNNING");invocation.setOriginalUserId(null);
        assertThrows(CostBudgetUnavailableException.class,()->tracking.intent(2L,"request",10L,"openai",
                com.yonagi.verse.common.enums.ModelOperation.CHAT_COMPLETIONS));
        invocation.setOriginalUserId(9L);
        var price=JSON.parseObject(attempt.getPricingJson(),PricingSnapshot.class);
        when(prices.resolve(eq(2L),eq(10L),any())).thenReturn(price);
        doAnswer(call->{call.getArgument(0,CostReconciliationAttemptDO.class).setId(32L);return 1;})
                .when(attempts).insert(any(CostReconciliationAttemptDO.class));
        assertEquals(32L,tracking.intent(2L,"request",10L,"openai",
                com.yonagi.verse.common.enums.ModelOperation.CHAT_COMPLETIONS));
        var saved=ArgumentCaptor.forClass(CostReconciliationAttemptDO.class);verify(attempts).insert(saved.capture());
        assertEquals("INTENT",saved.getValue().getOutcome());assertEquals("request",saved.getValue().getRequestId());
        assertEquals(price,JSON.parseObject(saved.getValue().getPricingJson(),PricingSnapshot.class));
    }

    @ParameterizedTest
    @CsvSource({"AUTO,1,7,1,true", "AUTO,2,7,1,false", "REVIEW,2,7,1,true",
            "REVIEW,1,7,1,false", "PENDING,1,7,1,false", "RESOLVED,1,7,1,false", "AUTO,1,8,1,false"})
    void replayRequiresOriginalCaseAndCurrentAutomaticVersion(String state, long version, long caseId,
            long expectedVersion, boolean allowed) {
        row.setState(state);
        row.setVersion(version);
        when(cases.selectOne(any())).thenReturn(row);
        assertEquals(allowed, tracking.acceptsReplay(invocation(), caseId, expectedVersion));
        row.setAttemptId(null);
        if ("REVIEW".equals(state)) {
            assertFalse(tracking.acceptsReplay(invocation(), caseId, expectedVersion));
        }
        when(cases.selectOne(any())).thenReturn(null);
        assertFalse(tracking.acceptsReplay(invocation(), caseId, expectedVersion));
        verify(cases, never()).updateById(any());
    }

    @Test
    void correctedConflictMarksReviewOnlyOnceAndKeepsOriginalCaseOwnership() {
        when(cases.selectOne(any())).thenReturn(row);
        assertFalse(tracking.retainCorrectedConflict(invocation()));
        assertNull(row.getReviewRequired());
        when(adjustments.exists(any())).thenReturn(true);
        assertTrue(tracking.retainCorrectedConflict(invocation()));
        assertTrue(tracking.retainCorrectedConflict(invocation()));
        assertTrue(row.getReviewRequired());
        assertEquals(2L, row.getVersion());
        assertEquals(8L, row.getBatchId());
        verify(cases).updateById(row);
        verifyNoInteractions(events, signals);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same", "amount", "missing-amount", "status", "model"})
    void lateOriginalOnlyFlagsActualDifferenceFromEffectiveCorrection(String difference) {
        when(cases.selectOne(any())).thenReturn(row);
        var original = new CostBudgetSettlementDO();
        original.setCostStatus("CALCULATED");
        original.setEstimatedCostFen(new BigDecimal("13.00"));
        original.setServiceId(10L);
        var correction = new CostReconciliationAdjustmentDO();
        correction.setCostStatus("CALCULATED");
        correction.setCostFen(new BigDecimal("13"));
        correction.setServiceId(10L);
        switch (difference) {
            case "amount" -> original.setEstimatedCostFen(new BigDecimal("14"));
            case "missing-amount" -> original.setEstimatedCostFen(null);
            case "status" -> original.setCostStatus("NOT_CHARGEABLE");
            case "model" -> original.setServiceId(11L);
            case "same" -> { }
            default -> throw new IllegalArgumentException("unknown scenario");
        }
        when(adjustments.latest(7L)).thenReturn(correction);

        tracking.reviewLateConflict(invocation(), original);
        tracking.reviewLateConflict(invocation(), original);

        boolean conflict = !"same".equals(difference);
        assertEquals(conflict, Boolean.TRUE.equals(row.getReviewRequired()));
        assertEquals(conflict ? 2L : 1L, row.getVersion());
        assertEquals(0, new BigDecimal("13").compareTo(correction.getCostFen()));
        assertEquals(10L, correction.getServiceId());
        assertEquals(8L, row.getBatchId());
        verify(cases, times(conflict ? 1 : 0)).updateById(row);
        verifyNoInteractions(events, signals);
    }

    @Test
    void missingCorrectionAndUnknownOriginalDoNotCreateReview() {
        when(cases.selectOne(any())).thenReturn(row);
        var original = new CostBudgetSettlementDO();
        original.setCostStatus("UNCALCULABLE");
        tracking.reviewLateConflict(invocation(), original);
        original.setCostStatus("NOT_CHARGEABLE");
        tracking.reviewLateConflict(invocation(), original);
        assertNull(row.getReviewRequired());
        assertEquals(1L, row.getVersion());
        verify(cases, never()).updateById(any());
    }

    @Test
    void verifiedAutomaticEvidenceIsRetainedOnlyForUnknownOriginalAndCalculableAttempt() {
        when(cases.selectOne(any())).thenReturn(row);
        row.setState("AUTO");
        var original = new CostBudgetSettlementDO();
        original.setCostStatus("UNCALCULABLE");
        assertFalse(tracking.retainsVerifiedAutomaticEvidence(invocation(), original));
        attempt.setUsageJson(JSON.toJSONString(new ReconciliationEvidence.Tokens(1_000_000L, 0L, 1_000_000L)));
        assertTrue(tracking.retainsVerifiedAutomaticEvidence(invocation(), original));
        row.setState("PENDING");
        assertFalse(tracking.retainsVerifiedAutomaticEvidence(invocation(), original));
        row.setState("AUTO");
        row.setAttemptId(null);
        assertFalse(tracking.retainsVerifiedAutomaticEvidence(invocation(), original));
        row.setAttemptId(31L);
        original.setCostStatus("CALCULATED");
        assertFalse(tracking.retainsVerifiedAutomaticEvidence(invocation(), original));
        original.setCostStatus("UNCALCULABLE");
        when(cases.selectOne(any())).thenReturn(null);
        assertFalse(tracking.retainsVerifiedAutomaticEvidence(invocation(), original));
        verify(cases, never()).updateById(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO", "REVIEW", "RESOLVED"})
    void confirmedOriginalClosesOnlyOpenCaseAndRepeatedEvidenceDoesNotAdvanceVersion(String state) {
        row.setState(state);
        when(cases.selectOne(any())).thenReturn(row);
        tracking.resolveFromOriginal(invocation(), CostStatus.CALCULATED);
        tracking.resolveFromOriginal(invocation(), CostStatus.CALCULATED);
        assertEquals("RESOLVED", row.getState());
        assertEquals("RESOLVED".equals(state) ? 1L : 2L, row.getVersion());
        assertEquals(8L, row.getBatchId());
        verify(cases, times("RESOLVED".equals(state) ? 0 : 1)).updateById(row);
        verify(signals, times("RESOLVED".equals(state) ? 0 : 1)).batchChanged(row);
    }

    @Test
    void unknownOriginalCannotClosePendingReconciliation() {
        when(cases.selectOne(any())).thenReturn(row);
        tracking.resolveFromOriginal(invocation(), CostStatus.UNCALCULABLE);
        assertEquals("PENDING", row.getState());
        assertEquals(1L, row.getVersion());
        verify(cases, never()).updateById(any());
        verifyNoInteractions(signals);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INTENT", "COMPLETED", "UNKNOWN", "NOT_SENT", "REJECTED"})
    void nextPhysicalAttemptRequiresProofThatPreviousAttemptCanBeRetried(String previousOutcome) {
        var invocation = invocation();
        invocation.setState("RUNNING");
        when(invocations.selectOne(any())).thenReturn(invocation);
        when(invocations.lockRequest("request")).thenReturn(invocation);
        when(prices.resolve(eq(2L), eq(10L), any()))
                .thenReturn(JSON.parseObject(attempt.getPricingJson(), PricingSnapshot.class));
        attempt.setOutcome(previousOutcome);
        attempt.setAttemptNo(1);
        when(attempts.latest("request")).thenReturn(attempt);
        boolean retryable = "NOT_SENT".equals(previousOutcome) || "REJECTED".equals(previousOutcome);
        if (retryable) {
            doAnswer(call -> { call.getArgument(0, CostReconciliationAttemptDO.class).setId(32L); return 1; })
                    .when(attempts).insert(any(CostReconciliationAttemptDO.class));
            assertEquals(32L, tracking.intent(2L, "request", 10L, "openai", ModelOperation.CHAT_COMPLETIONS));
            var created = ArgumentCaptor.forClass(CostReconciliationAttemptDO.class);
            verify(attempts).insert(created.capture());
            assertEquals(2, created.getValue().getAttemptNo());
            assertEquals("INTENT", created.getValue().getOutcome());
            assertEquals(attempt.getPricingJson(), created.getValue().getPricingJson());
        } else {
            assertThrows(CostBudgetUnavailableException.class,
                    () -> tracking.intent(2L, "request", 10L, "openai", ModelOperation.CHAT_COMPLETIONS));
            assertEquals(previousOutcome, attempt.getOutcome());
            verify(attempts, never()).insert(any(CostReconciliationAttemptDO.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-observation", "foreign-observation", "missing-locked", "changed-event",
            "foreign-locked"})
    void missingOrChangedOriginalRequestCannotObtainSendIntent(String failure) {
        var observed = invocation();
        observed.setState("RUNNING");
        observed.setEventId("original-event");
        var locked = JSON.parseObject(JSON.toJSONString(observed), CostBudgetInvocationDO.class);
        switch (failure) {
            case "foreign-observation" -> observed.setTenantId(99L);
            case "changed-event" -> locked.setEventId("new-event");
            case "foreign-locked" -> locked.setTenantId(99L);
            case "missing-observation", "missing-locked" -> { }
            default -> throw new IllegalArgumentException("unknown scenario");
        }
        when(invocations.selectOne(any())).thenReturn("missing-observation".equals(failure) ? null : observed);
        when(invocations.lockRequest("request")).thenReturn("missing-locked".equals(failure) ? null : locked);
        assertThrows(CostBudgetUnavailableException.class,
                () -> tracking.intent(2L, "request", 10L, "openai", ModelOperation.CHAT_COMPLETIONS));
        assertEquals("PENDING", row.getState());
        verify(attempts, never()).insert(any(CostReconciliationAttemptDO.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-attempt-id", "missing-request", "missing-attempt", "foreign-request",
            "rejected-attempt"})
    void completionCannotAttachEvidenceToAnotherOrUnavailablePhysicalAttempt(String failure) {
        if ("missing-request".equals(failure)) {
            when(invocations.lockRequest("request")).thenReturn(null);
        } else if ("missing-attempt".equals(failure)) {
            when(attempts.selectById(31L)).thenReturn(null);
        } else if ("foreign-request".equals(failure)) {
            attempt.setRequestId("another-request");
        } else if ("rejected-attempt".equals(failure)) {
            attempt.setOutcome("REJECTED");
        }
        tracking.completeAttempt("no-attempt-id".equals(failure) ? null : 31L, "request", exactUsage(0L));
        assertNull(attempt.getUsageJson());
        assertEquals("PENDING", row.getState());
        assertEquals(1L, row.getVersion());
        verify(attempts, never()).updateById(any());
        verifyNoInteractions(events, signals);
    }

    @Test
    void inactiveCaptureSkipsIntentAndMissingActivationMarkerFailsClosed() {
        when(scans.activationGeneration()).thenReturn(0L);
        assertNull(tracking.intent(2L, "request", 10L, "openai", ModelOperation.CHAT_COMPLETIONS));
        verify(attempts, never()).insert(any(CostReconciliationAttemptDO.class));
        when(scans.activationGeneration()).thenReturn(null);
        var invocation = invocation();
        assertThrows(IllegalStateException.class, () -> tracking.register(invocation, 9L));
        assertEquals(1L, invocation.getReconciliationGeneration());
    }

    private UsageBreakdown exactUsage(long cached) {
        return new UsageBreakdown(1_000_000L, cached, null, 1_000_000L, 2_000_000L,
                TokenUsageEvent.SOURCE_EXACT, "fixture", JSONObject.of("cached_tokens", cached), true);
    }
}
