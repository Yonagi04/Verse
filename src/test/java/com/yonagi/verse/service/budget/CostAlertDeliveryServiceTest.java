package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.messaging.*;
import com.yonagi.verse.service.notification.NotificationCreationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 任务编排使用依赖替身，仅验证发送选择和未知结果语义。 */
class CostAlertDeliveryServiceTest {
    final LlmCostAlertEventMapper events = mock(LlmCostAlertEventMapper.class);
    final LlmCostAlertDeliveryMapper tasks = mock(LlmCostAlertDeliveryMapper.class);
    final CostAlertDeliveryGuard guard = mock(CostAlertDeliveryGuard.class);
    final NotificationCreationService notifications = mock(NotificationCreationService.class);
    final TenantActivityRecorder activities = mock(TenantActivityRecorder.class);
    final SmsSendService sms = mock(SmsSendService.class);
    final EmailSendService email = mock(EmailSendService.class);
    final SmsSendRecordMapper smsRecords = mock(SmsSendRecordMapper.class);
    final EmailSendRecordMapper emailRecords = mock(EmailSendRecordMapper.class);
    final MessagingProperties properties = new MessagingProperties();
    final AesUtil aes = mock(AesUtil.class);
    final BudgetPeriodResolver time = new BudgetPeriodResolver(Clock.fixed(Instant.parse("2026-10-17T07:00:00Z"),ZoneOffset.UTC));
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final LlmCostAlertDeliveryService delivery = new LlmCostAlertDeliveryService(events,tasks,mock(LlmCostBudgetLedger.class),guard,notifications,activities,
            sms,email,smsRecords,emailRecords,properties,aes,time,new SimpleMeterRegistry(),manager);
    LlmCostAlertEventDO event; LlmCostAlertDeliveryDO task;
    @BeforeEach void setup() {
        var db = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (var type : List.of(LlmCostAlertEventDO.class,LlmCostAlertDeliveryDO.class,SmsSendRecordDO.class,EmailSendRecordDO.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(db,"delivery"),type);
        ReflectionTestUtils.setField(delivery,"frontendBaseUrl","https://verse.example/path");
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        properties.getSms().setProvider("sms-dev"); properties.getEmail().setProvider("mailpit");
        properties.getSms().setTenantCostWarningTemplateCode("10005"); properties.getSms().setTenantCostLimitTemplateCode("10006");
        event = new LlmCostAlertEventDO(); event.setEventId("event"); event.setTenantId(2L); event.setScopeType("TENANT"); event.setScopeId(2L); event.setScopeName("研发团队");
        event.setTenantName("研发团队"); event.setPeriodType("DAY"); event.setPeriodStart(time.local(time.now()).toLocalDate().atStartOfDay());
        event.setPeriodEnd(event.getPeriodStart().plusDays(1)); event.setEventType("LIMIT"); event.setTriggeredAt(time.local(time.now()));
        event.setLimitFen(new BigDecimal("100")); event.setUsedCostFen(new BigDecimal("100")); event.setRemainingCostFen(BigDecimal.ZERO); event.setRetryAt(event.getPeriodEnd()); event.setThresholdPercent(80); event.setNotificationId(100L);
        task = new LlmCostAlertDeliveryDO(); task.setId(1L); task.setEventId("event"); task.setRecipientUserId(1L); task.setChannel("SMS"); task.setStatus("PENDING"); task.setAttemptCount(0);
        task.setRequestId("stable-request"); task.setContactEncrypted("encrypted"); task.setCreateTime(time.local(time.now())); task.setNextRetryAt(task.getCreateTime());
        when(tasks.selectList(any())).thenReturn(List.of(task)); when(tasks.lock(1L)).thenReturn(task); when(events.selectOne(any())).thenReturn(event);
        when(guard.lockState(event)).thenReturn(state(true));
        when(aes.decrypt("encrypted")).thenReturn("13800138000");
        when(sms.send(any())).thenReturn(new MessageSubmissionRespDTO(MessageSubmissionStatus.QUEUED,"stable-request",null,null));
        when(email.send(any())).thenReturn(new MessageSubmissionRespDTO(MessageSubmissionStatus.QUEUED,"stable-request",null,null));
    }
    CostAlertDeliveryGuard.State state(boolean ready) {
        return new CostAlertDeliveryGuard.State(true,ready,true,80,List.of("SMS","EMAIL"),p -> BigDecimal.ONE,p -> null,
                () -> new CostBudgetDecision(List.of(new com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO.Hit("TENANT","DAY",time.iso(event.getPeriodEnd()))),false));
    }
    @ParameterizedTest @CsvSource({"TENANT,WARNING,10005","TENANT,LIMIT,10006","LLM,WARNING,10003","LLM,LIMIT,10004"})
    void templatesAndSceneFollowActualScopeWithoutInventingModel(String scope,String type,String template) {
        event.setScopeType(scope); event.setEventType(type); if ("LLM".equals(scope)) event.setServiceName("model");
        delivery.wake("event"); var request = ArgumentCaptor.forClass(SmsSendReqDTO.class); verify(sms).send(request.capture());
        assertEquals(scope+"_COST_"+type,request.getValue().scene()); assertEquals(template,request.getValue().templateCode());
        assertEquals("stable-request",request.getValue().requestId()); assertEquals("QUEUED",task.getStatus());
        if ("TENANT".equals(scope)) assertFalse(request.getValue().templateParams().containsKey("serviceName"));
    }
    @Test void tenantEmailUsesAuthenticatedSettingsLinkAndSnapshotAmounts() {
        task.setChannel("EMAIL"); when(aes.decrypt("encrypted")).thenReturn("admin@example.com"); delivery.wake("event");
        var request = ArgumentCaptor.forClass(EmailSendReqDTO.class); verify(email).send(request.capture());
        assertEquals("TENANT_COST_LIMIT",request.getValue().scene()); assertTrue(request.getValue().textBody().contains("https://verse.example/tenants/2?tab=settings"));
        assertTrue(request.getValue().subject().contains("租户成本限额已达到")); assertFalse(request.getValue().textBody().contains("租户已停用"));
    }
    @Test void queuedUnknownRecordOnlyMirrorsAndNeverResends() {
        task.setStatus("QUEUED"); var record = new SmsSendRecordDO(); record.setStatus("UNKNOWN"); when(smsRecords.selectOne(any())).thenReturn(record);
        delivery.wake("event"); assertEquals("UNKNOWN",task.getStatus()); verifyNoInteractions(sms,email);
    }
    @Test void unavailableAndInvalidStateDoNotSend() {
        when(guard.lockState(event)).thenReturn(state(false)); delivery.wake("event"); assertEquals("RETRY_WAIT",task.getStatus()); verifyNoInteractions(sms,email);
        task.setStatus("PENDING"); when(guard.lockState(event)).thenReturn(state(true)); when(guard.stateReason(eq(event),any())).thenReturn("STATE_CHANGED");
        delivery.wake("event"); assertEquals("CANCELLED",task.getStatus()); verifyNoInteractions(sms,email);
    }
    @Test void activityRemainsSystemFactAndAnnouncementContainsNoAmounts() {
        task.setChannel("ACTIVITY"); delivery.wake("event"); verify(activities).recordSystemSnapshot(event);
        task.setStatus("PENDING"); task.setChannel("IN_APP"); delivery.wake("event");
        var notification = ArgumentCaptor.forClass(NotificationEvent.class); verify(notifications).createAndPushIfAbsent(notification.capture());
        assertEquals(List.of(1L),notification.getValue().getRecipientUserIds()); assertTrue(notification.getValue().getTitle().startsWith("租户成本"));
        assertFalse(notification.getValue().getContent().contains("¥")); assertEquals("ACCEPTED",task.getStatus());
    }
}
