package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.pricing.CostResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证预算策略的可观察行为；数据库锁、回滚和 Outbox 原子性仍由 MySQL 集成测试验证。 */
class CostBudgetUnknownOutcomeTest {
    @ParameterizedTest
    @CsvSource({"FAIL,UNCALCULABLE,,UNKNOWN", "FAIL,NOT_CHARGEABLE,,SETTLED", "SUCCESS,UNCALCULABLE,,SETTLED",
            "ABORTED,CALCULATED,UNKNOWN,UNKNOWN", "FAIL,CALCULATED,COMPLETED,SETTLED",
            "ABORTED,UNPRICED,UNKNOWN,UNKNOWN", "FAIL,CALCULATED,UNKNOWN,UNKNOWN",
            "FAIL,UNCALCULABLE,UNKNOWN,UNKNOWN", "SUCCESS,CALCULATED,COMPLETED,SETTLED"})
    void failureWithUnknownCostBlocksBudgetAndReapplyingDoesNotReleaseFence(
            String status, CostStatus cost, String outcome, String expectedState) {
        var keys = mock(ApiKeyMapper.class);
        var invocations = mock(CostBudgetInvocationMapper.class);
        var settlements = mock(CostBudgetSettlementMapper.class);
        var periods = mock(CostBudgetPeriodMapper.class);
        var outbox = mock(UsageOutboxStager.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        Instant now = Instant.parse("2026-10-07T01:00:00Z");
        var service = new CostBudgetService(keys, invocations, settlements, periods,
                new BudgetPeriodResolver(Clock.fixed(now, ZoneOffset.UTC)), mock(BudgetExecutionRegistry.class),
                outbox, new UsageOutboxProperties(), manager, mock(CostBudgetAudit.class));
        ReflectionTestUtils.setField(service, "costingEnabled", true);
        var key = new ApiKeyDO(); key.setTenantId(2L); key.setApiKeyId(3L); key.setUserId(1L);
        key.setCostLimitEnabled(true); key.setCostLimitDailyFen(new BigDecimal("100")); key.setCostDataState("READY");
        when(keys.lockBudgetKey(2L, 3L)).thenReturn(key);
        var invocation = new CostBudgetInvocationDO(); invocation.setRequestId("request"); invocation.setState("FINALIZING");
        when(invocations.lockRequest("request")).thenReturn(invocation);
        when(invocations.unfinished(eq(2L), eq(3L), any())).thenAnswer(ignored ->
                "SETTLED".equals(invocation.getState()) ? List.of() : List.of(invocation));
        var event = JSON.parseObject(outcome == null ? "{}" : "{\"executionOutcome\":\"" + outcome + "\"}", TokenUsageEvent.class);
        event.setRequestId("request"); event.setStatus(status);
        event.setRequestStartedAt(now); event.setCostResult(cost == CostStatus.CALCULATED
                ? new CostResult(cost, BigDecimal.ONE) : CostResult.of(cost));
        var row = new CostBudgetSettlementDO(); row.setEventId("event"); row.setTenantId(2L); row.setApiKeyId(3L);
        row.setRequestId("request"); row.setRequestStartedAt(now.atZone(BudgetPeriodResolver.ZONE).toLocalDateTime());
        row.setCostStatus(cost.name()); row.setOrigin("LIVE"); row.setBudgetApplied(false);
        if (cost == CostStatus.CALCULATED) row.setEstimatedCostFen(BigDecimal.ONE);
        row.setEventPayloadJson(JSON.toJSONString(event));
        when(settlements.selectOne(any())).thenReturn(row); when(settlements.lockEvent("event")).thenReturn(row);

        service.apply("event"); service.apply("event");

        assertEquals(expectedState, invocation.getState()); assertTrue(row.getBudgetApplied());
        verify(outbox, times(1)).stage(any(), any(), any());
        var context = new UserContext().setCurrentTenantId(2L).setApiKeyId(3L);
        if ("UNKNOWN".equals(expectedState)) {
            assertEquals("UPSTREAM_RESULT_UNKNOWN", invocation.getLastErrorCode());
            assertThrows(CostBudgetUnavailableException.class, () -> service.check(context));
        } else assertDoesNotThrow(() -> service.check(context));
    }
}
