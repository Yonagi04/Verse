package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostAlertDeliveryGuardTest {
    final LlmCostBudgetLedger llm = mock(LlmCostBudgetLedger.class);
    final TenantCostBudgetLedger tenant = mock(TenantCostBudgetLedger.class);
    final TenantMapper tenants = mock(TenantMapper.class);
    final LlmCostAlertEventMapper events = mock(LlmCostAlertEventMapper.class);
    final LlmCostAlertDeliveryMapper tasks = mock(LlmCostAlertDeliveryMapper.class);
    final UserMapper users = mock(UserMapper.class);
    final BudgetPeriodResolver time = new BudgetPeriodResolver(Clock.fixed(Instant.parse("2026-10-17T07:00:00Z"),ZoneOffset.UTC));
    final CostAlertDeliveryGuard guard = new CostAlertDeliveryGuard(llm,tenant,tenants,events,tasks,users,time);
    LlmCostAlertEventDO event; LlmCostAlertDeliveryDO task; TenantCostBudgetDO config; CostBudgetPeriodDO period;
    @BeforeEach void setup() {
        var db = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (var type : List.of(UserDO.class,LlmCostAlertEventDO.class,LlmCostAlertDeliveryDO.class)) com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(db,"guard"),type);
        event = new LlmCostAlertEventDO(); event.setEventId("e"); event.setTenantId(2L); event.setScopeType("TENANT"); event.setScopeId(2L);
        event.setPeriodType("DAY"); event.setPeriodStart(time.resolve(time.now()).getFirst().start()); event.setPeriodEnd(time.resolve(time.now()).getFirst().end()); event.setEventType("LIMIT");
        task = new LlmCostAlertDeliveryDO(); task.setId(1L); task.setEventId("e"); task.setRecipientUserId(1L); task.setChannel("EMAIL"); task.setStatus("QUEUED"); task.setContactHash("hash");
        config = new TenantCostBudgetDO(); config.setCostLimitEnabled(true); config.setDailyLimitFen(new BigDecimal("100")); config.setWarningEnabled(true); config.setWarningThresholdPercent(80); config.setWarningChannels("[\"EMAIL\"]");
        period = new CostBudgetPeriodDO(); period.setUsedCostFen(new BigDecimal("100"));
        when(tenant.lock(2L)).thenReturn(config); when(tenant.ready(eq(config),any())).thenReturn(true); when(tenant.load(eq(config),any())).thenReturn(period);
        when(tasks.selectOne(any())).thenReturn(task); when(tasks.lock(1L)).thenReturn(task); when(events.selectOne(any())).thenReturn(event);
        when(tenants.recipientRole(2L,1L)).thenReturn("ADMIN"); var user = new UserDO(); user.setEmailHash("hash"); when(users.selectOne(any())).thenReturn(user);
    }
    @Test void eligibleTenantDeliveryAndModelDeliveryUseOwnScope() {
        assertNull(guard.verify("TENANT_COST_LIMIT","r")); verify(tenant).lock(2L); verifyNoInteractions(llm);
        var model = new LlmCostBudgetDO(); model.setCostLimitEnabled(true); model.setWarningEnabled(true); model.setWarningThresholdPercent(80); model.setWarningChannels("[\"EMAIL\"]");
        model.setDailyLimitFen(new BigDecimal("100")); event.setScopeType("LLM"); event.setServiceId(10L);
        when(llm.lock(2L,10L)).thenReturn(model); when(llm.ready(eq(model),any())).thenReturn(true); when(llm.load(eq(model),any())).thenReturn(period);
        assertNull(guard.verify("LLM_COST_LIMIT","r")); verify(llm).lock(2L,10L);
        assertNull(guard.verify("RESET_PASSWORD","r"));
    }
    @ParameterizedTest @CsvSource({"MEMBER,EMAIL,ROLE_CHANGED","NONE,EMAIL,RECIPIENT_LEFT","MEMBER,IN_APP,OK","ADMIN,SMS,CONTACT_CHANGED"})
    void recipientsRecheckedWithoutGrantingMemberFinancialNotifications(String role,String channel,String expected) {
        when(tenants.recipientRole(2L,1L)).thenReturn("NONE".equals(role) ? null : role); task.setChannel(channel);
        var result = guard.verify("TENANT_COST_LIMIT","r");
        if ("OK".equals(expected)) assertNull(result); else assertEquals(expected,result.errorCode());
    }
    @Test void budgetUnavailableDoesNotCancelOrSendAndChangedStateCancels() {
        when(tenant.ready(eq(config),any())).thenReturn(false); assertTrue(guard.verify("TENANT_COST_LIMIT","r").retryable());
        when(tenant.ready(eq(config),any())).thenReturn(true); period.setUsedCostFen(BigDecimal.ZERO);
        assertEquals("STATE_CHANGED",guard.verify("TENANT_COST_LIMIT","r").errorCode());
        config.setCostLimitEnabled(false); assertEquals("BUDGET_DISABLED",guard.verify("TENANT_COST_LIMIT","r").errorCode());
        event.setPeriodEnd(time.local(time.now())); assertEquals("PERIOD_EXPIRED",guard.verify("TENANT_COST_LIMIT","r").errorCode());
    }
    @Test void removedChannelChangedContactAndCancelledTaskCannotSend() {
        event.setEventType("WARNING"); period.setUsedCostFen(new BigDecimal("80")); config.setWarningChannels("[\"IN_APP\"]");
        assertEquals("CHANNEL_REMOVED",guard.verify("TENANT_COST_WARNING","r").errorCode());
        config.setWarningChannels("[\"EMAIL\"]"); when(users.selectOne(any())).thenReturn(null);
        assertEquals("CONTACT_CHANGED",guard.verify("TENANT_COST_WARNING","r").errorCode());
        task.setStatus("CANCELLED"); assertEquals("DELIVERY_CANCELLED",guard.verify("TENANT_COST_WARNING","r").errorCode());
    }
    @Test void missingTaskOrEventIsExplicitCancellation() {
        when(tasks.selectOne(any())).thenReturn(null); assertEquals("DELIVERY_MISSING",guard.verify("TENANT_COST_LIMIT","r").errorCode());
        when(tasks.selectOne(any())).thenReturn(task); when(events.selectOne(any())).thenReturn(null); assertEquals("EVENT_MISSING",guard.verify("TENANT_COST_LIMIT","r").errorCode());
    }
}
