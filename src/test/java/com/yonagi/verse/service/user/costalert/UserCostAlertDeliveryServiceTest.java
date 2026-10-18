package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.yonagi.verse.support.UserUsageTestFixtures;
import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.common.config.*;
import com.yonagi.verse.common.enums.MessageChannel;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.messaging.*;
import com.yonagi.verse.service.notification.NotificationCreationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCostAlertDeliveryServiceTest {
    final UserCostAlertDeliveryProperties properties=new UserCostAlertDeliveryProperties();
    final UserModelUsageAccessPolicy access=mock(UserModelUsageAccessPolicy.class);
    final UserCostAlertConfigMapper configs=mock(UserCostAlertConfigMapper.class);
    final UserCostAlertEventMapper events=mock(UserCostAlertEventMapper.class);
    final UserCostAlertDeliveryMapper deliveries=mock(UserCostAlertDeliveryMapper.class);
    final UserCostAlertSendEligibility guard=mock(UserCostAlertSendEligibility.class);
    final UserCostAlertChannelReadinessService readiness=mock(UserCostAlertChannelReadinessService.class);
    final NotificationCreationService notifications=mock(NotificationCreationService.class);
    final SendRecordStore records=mock(SendRecordStore.class);
    final SmsSendService sms=mock(SmsSendService.class);
    final EmailSendService email=mock(EmailSendService.class);
    final MessagingProperties messaging=new MessagingProperties();
    final UserCostAlertDeliveryService service=new UserCostAlertDeliveryService(properties,access,configs,events,deliveries,guard,readiness,notifications,records,sms,email,
            messaging,UserUsageTestFixtures.TIME,new SimpleMeterRegistry(),UserUsageTestFixtures.manager());
    UserCostAlertDeliveryDO task; UserCostAlertEventDO event;
    @BeforeEach void setup() {
        UserUsageTestFixtures.tables(); properties.setDeliveryEnabled(true); task=UserUsageTestFixtures.task(); task.setStatus("PENDING"); event=UserUsageTestFixtures.event();
        when(access.lock(1L)).thenReturn(UserUsageTestFixtures.user()); when(configs.selectById(1L)).thenReturn(UserUsageTestFixtures.config()); when(events.selectById("e")).thenReturn(event);
        when(guard.lock(2L)).thenReturn(task); when(records.findSubmission(any(),anyString())).thenReturn(Optional.empty());
        when(readiness.readiness(any())).thenReturn(List.of(new UserCostAlertSettingsRespDTO.ChannelReadiness("EMAIL",true,null,null)));
        when(readiness.hash(any(),eq("EMAIL"))).thenReturn("hash"); when(readiness.contact(any(),eq("EMAIL"))).thenReturn("a@example.test");
        ReflectionTestUtils.setField(service,"frontendBaseUrl","https://verse.example.test");
    }
    @Test void selectedEmailUsesFixedRequestIdAndNeverAddsInAppOrSms() {
        when(email.send(any())).thenReturn(MessageSubmissionRespDTO.queued()); service.dispatch(task);
        var request=ArgumentCaptor.forClass(EmailSendReqDTO.class); verify(email).send(request.capture());
        assertEquals("r",request.getValue().requestId()); assertEquals(1L,request.getValue().userId()); assertNull(request.getValue().tenantId());
        assertTrue(request.getValue().textBody().contains("所有租户")); assertEquals("QUEUED",task.getStatus()); verifyNoInteractions(sms,notifications);
    }
    @Test void durableMessageStatusWinsWhenEnqueueAcknowledgementIsLost() {
        when(records.findSubmission(any(),anyString())).thenReturn(Optional.empty(),Optional.of(new SendRecordStore.SubmissionSnapshot("ACCEPTED",null,"hash",null)));
        when(email.send(any())).thenThrow(new IllegalStateException("commit acknowledgement lost")); service.dispatch(task); assertEquals("ACCEPTED",task.getStatus());
        verify(email,times(1)).send(any()); verifyNoInteractions(sms,notifications);
    }
    @Test void inProgressIsQueuedAndUnknownIsTerminalWithoutResending() {
        when(records.findSubmission(any(),anyString())).thenReturn(Optional.of(new SendRecordStore.SubmissionSnapshot("SUBMITTING",null,"hash","t")));
        service.dispatch(task); assertEquals("QUEUED",task.getStatus()); verifyNoInteractions(email);
        when(records.findSubmission(any(),anyString())).thenReturn(Optional.of(new SendRecordStore.SubmissionSnapshot("UNKNOWN","TIMEOUT","hash",null)));
        service.dispatch(task); assertEquals("UNKNOWN",task.getStatus()); service.dispatch(task); verifyNoInteractions(email);
    }
    @Test void uncertainEnqueueRetriesOnlyWithinBoundsThenReportsUnknown() {
        when(email.send(any())).thenThrow(new IllegalStateException("unavailable")); service.dispatch(task); assertEquals("RETRY_WAIT",task.getStatus());
        task.setAttemptCount(2); task.setNextRetryAt(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now())); service.dispatch(task);
        assertEquals("UNKNOWN",task.getStatus()); assertEquals("ENQUEUE_UNCERTAIN",task.getReasonCode());
    }
    @Test void inAppHasOneGlobalSelfRecipientAndPreservesFixedNotificationId() {
        task.setChannel("IN_APP"); service.dispatch(task); var notification=ArgumentCaptor.forClass(NotificationEvent.class); verify(notifications).createAndPushIfAbsent(notification.capture());
        assertEquals(9L,notification.getValue().getNotificationId()); assertNull(notification.getValue().getTenantId()); assertEquals(List.of(1L),notification.getValue().getRecipientUserIds());
        assertEquals("ACCEPTED",task.getStatus()); verifyNoInteractions(email,sms,records);
    }
    @Test void cancelledContactAndExpiredStateNeverEnqueue() {
        task.setCancelRequestedAt(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now())); service.dispatch(task); assertEquals("CANCELLED",task.getStatus()); verifyNoInteractions(email,sms);
        task.setCancelRequestedAt(null); task.setStatus("PENDING"); when(guard.stateReason(eq(event),any(),eq("EMAIL"))).thenReturn("PERIOD_EXPIRED");
        service.dispatch(task); assertEquals("CANCELLED",task.getStatus()); assertEquals("PERIOD_EXPIRED",task.getReasonCode());
    }

    @Test void selectedSmsUsesAuditedTemplateAndStableSelfIdentityForWeeklyAlert() {
        task.setChannel("SMS"); event.setPeriodType("WEEK"); event.setCostComplete(true); messaging.getSms().setPersonalCostWarningTemplateCode("cost_week");
        when(readiness.readiness(any())).thenReturn(List.of(new UserCostAlertSettingsRespDTO.ChannelReadiness("SMS",true,null,null)));
        when(readiness.hash(any(),eq("SMS"))).thenReturn("hash"); when(readiness.contact(any(),eq("SMS"))).thenReturn("13800138000");
        when(sms.send(any())).thenReturn(MessageSubmissionRespDTO.accepted("provider-r","message")); service.dispatch(task);
        var request=ArgumentCaptor.forClass(SmsSendReqDTO.class); verify(sms).send(request.capture());
        assertEquals("r",request.getValue().requestId()); assertEquals(UserCostAlertSendEligibility.SCENE,request.getValue().scene());
        assertEquals(1L,request.getValue().userId()); assertNull(request.getValue().tenantId()); assertEquals("cost_week",request.getValue().templateCode());
        assertEquals("周",request.getValue().templateParams().get("period")); assertEquals("12.00",request.getValue().templateParams().get("amount"));
        assertEquals("10.00",request.getValue().templateParams().get("threshold")); assertEquals("ACCEPTED",task.getStatus()); verifyNoInteractions(email,notifications);
    }

    @Test void provenNotSentFailureRetriesWithinWindowAndExpiresWithoutUnknownOutcome() {
        when(email.send(any())).thenReturn(MessageSubmissionRespDTO.retryable("PROVIDER_NOT_CALLED"));
        service.dispatch(task); assertEquals("RETRY_WAIT",task.getStatus()); assertEquals("PROVIDER_NOT_CALLED",task.getReasonCode());
        task.setAttemptCount(2); service.dispatch(task); assertEquals("EXPIRED",task.getStatus());
        service.dispatch(task); verify(email,times(2)).send(any());
    }

    @Test void activeLeaseDoesNotDuplicateEnqueueAndLostQueuedRecordIsTerminalUnknown() {
        task.setStatus("CLAIMED"); task.setLeaseUntil(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now()).plusSeconds(1));
        service.dispatch(task); verifyNoInteractions(email,records);
        task.setStatus("QUEUED"); service.dispatch(task); assertEquals("UNKNOWN",task.getStatus()); assertEquals("SEND_RECORD_MISSING",task.getReasonCode()); verifyNoInteractions(email);
    }

    @Test void unavailableOrChangedContactAndExpiredTaskCannotBeSent() {
        when(readiness.readiness(any())).thenReturn(List.of()); service.dispatch(task); assertEquals("CHANNEL_UNAVAILABLE",task.getReasonCode());
        task.setStatus("PENDING"); when(readiness.readiness(any())).thenReturn(List.of(new UserCostAlertSettingsRespDTO.ChannelReadiness("EMAIL",true,null,null)));
        when(readiness.hash(any(),eq("EMAIL"))).thenReturn("other"); service.dispatch(task); assertEquals("CONTACT_CHANGED",task.getReasonCode());
        task.setStatus("PENDING"); when(readiness.hash(any(),eq("EMAIL"))).thenReturn(null); service.dispatch(task); assertEquals("CONTACT_CHANGED",task.getReasonCode());
        task.setStatus("PENDING"); task.setCreateTime(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now()).minusSeconds(60));
        service.dispatch(task); assertEquals("EXPIRED",task.getStatus()); assertEquals("TASK_EXPIRED",task.getReasonCode()); verifyNoInteractions(email,sms);
    }
}
