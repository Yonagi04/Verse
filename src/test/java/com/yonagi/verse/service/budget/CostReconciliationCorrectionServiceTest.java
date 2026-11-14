package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.CostReconciliationResolveReqDTO;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CostReconciliationCorrectionServiceTest {
    @Test
    void correctionKeepsFirstBatchAndCaseOwnershipAndReliablyWakesOriginalNotification() {
        var invocations=mock(CostBudgetInvocationMapper.class);
        var settlements=mock(CostBudgetSettlementMapper.class);
        var cases=mock(CostReconciliationCaseMapper.class);
        var attempts=mock(CostReconciliationAttemptMapper.class);
        var adjustments=mock(CostReconciliationAdjustmentMapper.class);
        var budgets=mock(CostBudgetService.class);
        var models=mock(LlmCostBudgetLedger.class);
        var events=mock(ReliableDomainEventPublisher.class);
        var signals=mock(CostReconciliationSignals.class);
        var row=new CostReconciliationCaseDO();row.setId(3L);row.setBatchId(4L);row.setTenantId(7L);
        row.setUserId(9L);row.setRequestId("request");row.setEventId("event");row.setSource("PLAYGROUND");
        row.setRequestStartedAt(LocalDateTime.of(2026,11,13,8,0));row.setAttemptId(5L);row.setState("PENDING");row.setVersion(0L);
        var invocation=new CostBudgetInvocationDO();invocation.setTenantId(7L);invocation.setEventId("event");
        invocation.setRequestId("request");invocation.setSource("PLAYGROUND");invocation.setRequestStartedAt(row.getRequestStartedAt());
        var attempt=new CostReconciliationAttemptDO();attempt.setId(5L);attempt.setServiceId(10L);attempt.setRequestId("request");
        attempt.setOperation("CHAT_COMPLETIONS");attempt.setOutcome("UNKNOWN");
        attempt.setPricingJson(JSON.toJSONString(new PricingSnapshot(1L,BillingMode.REQUEST,"CNY",null,null,
                null,null,null,new BigDecimal("13"),null,null)));
        when(attempts.selectById(5L)).thenReturn(attempt);when(invocations.lockRequest("request")).thenReturn(invocation);
        when(cases.lockInBatch(4L,3L)).thenReturn(row);when(models.lockForRequest(7L,"request",10L)).thenReturn(List.of());
        doAnswer(call->{call.getArgument(0,CostReconciliationAdjustmentDO.class).setId(20L);return 1;})
                .when(adjustments).insert(any(CostReconciliationAdjustmentDO.class));
        var service=new CostReconciliationCorrectionService(budgets,mock(TenantCostBudgetLedger.class),models,invocations,
                settlements,cases,attempts,adjustments,mock(UserMapper.class),mock(TenantAccessPolicy.class),
                mock(LlmCostBudgetConfigurationService.class),new ReconciliationEvidence(new CostCalculator()),
                events,signals,new BudgetPeriodResolver());
        var command=new CostReconciliationResolveReqDTO(CostReconciliationResolveReqDTO.Decision.CHARGE,
                true,0L,"submit-001",null,null,null);
        var saved=service.resolve(row,11L,command);
        assertEquals(new BigDecimal("13"),saved.getCostFen());assertEquals("CALCULATED",saved.getCostStatus());
        assertEquals("RESOLVED",row.getState());assertEquals(4L,row.getBatchId());
        assertEquals("CASE",invocation.getReconciliationDisposition());assertEquals("SETTLED",invocation.getState());
        verify(signals).batchChanged(row);
        var event=ArgumentCaptor.forClass(CostReconciliationEvent.class);
        verify(events).publish(event.capture(),eq(7L));assertEquals(20L,event.getValue().getAdjustmentId());
    }
}
