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

    private UsageBreakdown exactUsage(long cached) {
        return new UsageBreakdown(1_000_000L, cached, null, 1_000_000L, 2_000_000L,
                TokenUsageEvent.SOURCE_EXACT, "fixture", JSONObject.of("cached_tokens", cached), true);
    }
}
