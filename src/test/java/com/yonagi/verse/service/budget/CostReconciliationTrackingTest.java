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
import com.yonagi.verse.dao.entity.CostReconciliationWorkDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAdjustmentMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAttemptMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationWorkMapper;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.usage.UsageBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostReconciliationTrackingTest {
    private final CostReconciliationScanMapper scans = mock(CostReconciliationScanMapper.class);
    private final CostReconciliationWorkMapper work = mock(CostReconciliationWorkMapper.class);
    private final CostReconciliationAttemptMapper attempts = mock(CostReconciliationAttemptMapper.class);
    private final CostReconciliationCaseMapper cases = mock(CostReconciliationCaseMapper.class);
    private final CostReconciliationAdjustmentMapper adjustments = mock(CostReconciliationAdjustmentMapper.class);
    private final CostBudgetInvocationMapper invocations = mock(CostBudgetInvocationMapper.class);
    private final ReliableDomainEventPublisher events = mock(ReliableDomainEventPublisher.class);
    private final BudgetPeriodResolver time = new BudgetPeriodResolver(
            Clock.fixed(Instant.parse("2026-11-08T07:00:00Z"), ZoneOffset.UTC));
    private final CostReconciliationTracking tracking = new CostReconciliationTracking(scans, work, attempts, cases,
            adjustments, invocations, null, new ReconciliationEvidence(new CostCalculator()), time, events);
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
        var task = ArgumentCaptor.forClass(CostReconciliationWorkDO.class);
        verify(work).insert(task.capture());
        assertTrue(task.getValue().getAutoQueued());
        assertNull(task.getValue().getScanId());
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
        verifyNoInteractions(events, work);
    }

    private UsageBreakdown exactUsage(long cached) {
        return new UsageBreakdown(1_000_000L, cached, null, 1_000_000L, 2_000_000L,
                TokenUsageEvent.SOURCE_EXACT, "fixture", JSONObject.of("cached_tokens", cached), true);
    }
}
