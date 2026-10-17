package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dao.projection.CostAlertRecipient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证事件和收件业务规则，不证明 SQL 唯一约束或外部发送。 */
class CostAlertCaptureServiceTest {
    final LlmCostAlertEventMapper events = mock(LlmCostAlertEventMapper.class);
    final LlmCostAlertDeliveryMapper deliveries = mock(LlmCostAlertDeliveryMapper.class);
    final LlmServiceMapper services = mock(LlmServiceMapper.class);
    final ReliableDomainEventPublisher publisher = mock(ReliableDomainEventPublisher.class);
    final BudgetPeriodResolver time = new BudgetPeriodResolver(Clock.fixed(Instant.parse("2026-10-17T07:00:00Z"),ZoneOffset.UTC));
    final LlmCostAlertCaptureService capture = new LlmCostAlertCaptureService(events,deliveries,services,publisher,time);
    TenantCostBudgetDO tenant; LlmCostBudgetDO model; CostBudgetPeriodDO day;
    @BeforeEach void setup() {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (var type : List.of(LlmCostAlertEventDO.class,LlmServiceDO.class)) com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(config,"alert-capture"),type);
        var t = new TenantDO(); t.setName("研发团队"); t.setActivityRecordingEnabled(1); when(events.tenantSnapshot(2L)).thenReturn(t);
        var m = new LlmServiceDO(); m.setName("模型"); when(services.selectOne(any())).thenReturn(m);
        tenant = new TenantCostBudgetDO(); tenant.setTenantId(2L); tenant.setCostLimitEnabled(true); tenant.setDailyLimitFen(new BigDecimal("100"));
        tenant.setWarningEnabled(true); tenant.setWarningThresholdPercent(80); tenant.setWarningChannels("[\"EMAIL\"]"); tenant.setConfigVersion(3L);
        model = new LlmCostBudgetDO(); model.setTenantId(2L); model.setServiceId(10L); model.setCostLimitEnabled(true); model.setDailyLimitFen(new BigDecimal("100"));
        model.setWarningEnabled(true); model.setWarningThresholdPercent(80); model.setWarningChannels("[\"EMAIL\"]"); model.setConfigVersion(3L);
        day = new CostBudgetPeriodDO(); day.setPeriodType("DAY"); day.setPeriodStart(time.resolve(time.now()).getFirst().start());
        day.setPeriodEnd(time.resolve(time.now()).getFirst().end()); day.setUsedCostFen(new BigDecimal("100"));
        when(deliveries.recipientSnapshot(2L)).thenReturn(List.of(recipient(1L,"ADMIN"),recipient(2L,"MEMBER")));
    }
    private CostAlertRecipient recipient(Long id,String role) {
        var r = new CostAlertRecipient(); r.setUserId(id); r.setRole(role); r.setEmail("encrypted"); r.setEmailHash("hash"); return r;
    }
    @ParameterizedTest @CsvSource({"TENANT,80,WARNING","TENANT,100,LIMIT","LLM,80,WARNING","LLM,100,LIMIT"})
    void scopesRemainIndependentAndLimitForcesAllChannels(String scope,String used,String type) {
        day.setUsedCostFen(new BigDecimal(used));
        List<LlmCostAlertDeliveryDO> tasks = new ArrayList<>();
        doAnswer(call -> { tasks.addAll(call.getArgument(0)); return null; }).when(deliveries).insertBatch(anyList());
        if ("TENANT".equals(scope)) capture.capture(tenant,List.of(day)); else capture.capture(model,List.of(day));
        var event = ArgumentCaptor.forClass(LlmCostAlertEventDO.class); verify(events).insert(event.capture());
        var fact = event.getValue(); assertEquals(scope,fact.getScopeType()); assertEquals(type,fact.getEventType());
        assertEquals("TENANT".equals(scope) ? 2L : 10L,fact.getScopeId()); assertEquals("TENANT".equals(scope) ? null : 10L,fact.getServiceId());
        assertEquals("TENANT".equals(scope) ? null : "模型",fact.getServiceName());
        if ("WARNING".equals(type)) { assertEquals(1,tasks.size()); assertEquals("EMAIL",tasks.getFirst().getChannel()); assertEquals("20",fact.getRemainingCostFen().toPlainString()); }
        else {
            assertEquals(Set.of("IN_APP","SMS","EMAIL"),tasks.stream().map(LlmCostAlertDeliveryDO::getChannel).collect(java.util.stream.Collectors.toSet()));
            assertEquals(2,tasks.stream().filter(t -> "IN_APP".equals(t.getChannel())).count());
            assertTrue(tasks.stream().filter(t -> "SMS".equals(t.getChannel())).allMatch(t -> "SKIPPED_NO_CONTACT".equals(t.getReasonCode())));
            verify(events).cancelWarning(eq(2L),eq(scope),eq(fact.getScopeId()),eq("DAY"),any(),any());
        }
        verify(publisher).publish(any(),eq(2L));
        String publicText = LlmCostAlertTemplates.publicBody(fact,"2026-10-18T00:00:00+08:00");
        assertFalse(publicText.contains("¥")); assertFalse(publicText.contains("encrypted"));
        if ("TENANT".equals(scope)) { assertTrue(LlmCostAlertTemplates.title(fact).startsWith("租户成本")); assertFalse(LlmCostAlertTemplates.sms(fact).containsKey("serviceName")); }
    }
    @Test void belowThresholdExpiredAndExistingFactsDoNotNotifyAgain() {
        day.setUsedCostFen(new BigDecimal("79.999")); capture.capture(tenant,List.of(day));
        day.setUsedCostFen(new BigDecimal("100")); day.setPeriodEnd(time.local(time.now())); capture.capture(tenant,List.of(day));
        day.setPeriodEnd(time.local(time.now()).plusDays(1)); when(events.selectOne(any())).thenReturn(new LlmCostAlertEventDO()); capture.capture(tenant,List.of(day));
        verify(events,never()).insert(any(LlmCostAlertEventDO.class)); verifyNoInteractions(deliveries,publisher);
    }
    @Test void disabledActivityAndEmptyRecipientsStillRecordForcedChannelOutcomes() {
        var t = new TenantDO(); t.setName("team"); t.setActivityRecordingEnabled(0); when(events.tenantSnapshot(2L)).thenReturn(t);
        when(deliveries.recipientSnapshot(2L)).thenReturn(List.of()); capture.capture(tenant,List.of(day));
        var tasks = ArgumentCaptor.forClass(LlmCostAlertDeliveryDO.class); verify(deliveries,times(4)).insert(tasks.capture());
        assertEquals(3,tasks.getAllValues().stream().filter(tk -> "SKIPPED_NO_RECIPIENT".equals(tk.getReasonCode())).count());
        assertTrue(tasks.getAllValues().stream().anyMatch(tk -> "ACTIVITY".equals(tk.getChannel()) && "SKIPPED".equals(tk.getStatus())));
    }
}
