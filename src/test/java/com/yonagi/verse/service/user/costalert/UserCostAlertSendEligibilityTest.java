package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.yonagi.verse.support.UserUsageTestFixtures;
import com.yonagi.verse.common.config.UserCostAlertDeliveryProperties;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.UserCostAlertSettingsRespDTO.ChannelReadiness;
import com.yonagi.verse.service.messaging.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCostAlertSendEligibilityTest {
    final UserCostAlertDeliveryProperties properties=new UserCostAlertDeliveryProperties();
    final UserModelUsageAccessPolicy access=mock(UserModelUsageAccessPolicy.class);
    final UserCostAlertConfigMapper configs=mock(UserCostAlertConfigMapper.class);
    final UserCostAlertEventMapper events=mock(UserCostAlertEventMapper.class);
    final UserCostAlertDeliveryMapper deliveries=mock(UserCostAlertDeliveryMapper.class);
    final UserCostAlertChannelReadinessService readiness=mock(UserCostAlertChannelReadinessService.class);
    final SendRecordStore records=mock(SendRecordStore.class);
    final UserCostAlertSendEligibility guard=new UserCostAlertSendEligibility(properties,access,configs,events,deliveries,readiness,records,UserUsageTestFixtures.TIME);
    final SendAttemptContext attempt=new SendAttemptContext(MessageChannel.EMAIL,8L,"attempt-1",1,"hash");
    UserCostAlertDeliveryDO task; UserCostAlertConfigDO config; UserCostAlertEventDO event;
    @BeforeEach void setup() {
        UserUsageTestFixtures.tables(); properties.setDeliveryEnabled(true); task=UserUsageTestFixtures.task(); config=UserUsageTestFixtures.config(); event=UserUsageTestFixtures.event();
        when(access.lock(1L)).thenReturn(UserUsageTestFixtures.user()); when(configs.selectById(1L)).thenReturn(config); when(events.selectById("e")).thenReturn(event);
        when(deliveries.selectOne(any())).thenReturn(task); when(readiness.hash(any(),eq("EMAIL"))).thenReturn("hash");
        when(readiness.readiness(any())).thenReturn(List.of(new ChannelReadiness("EMAIL",true,null,null)));
        when(records.lockAttempt(eq(UserCostAlertSendEligibility.SCENE),eq("r"),eq(1L),eq(attempt))).thenReturn(true);
    }
    @Test void onlyVerifiedCurrentAttemptGetsPermitAndOldEntryCannotAuthorize() {
        assertEquals("ATTEMPT_CONTEXT_REQUIRED",guard.verify(UserCostAlertSendEligibility.SCENE,"r").errorCode());
        assertNull(guard.verify("RESET_PASSWORD","r")); assertNull(guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt));
        assertEquals("attempt-1",task.getPermittedSendToken()); assertEquals(1,task.getPermittedSendAttempt()); assertNotNull(task.getSendPermittedAt());
        verify(deliveries).updateById(task);
    }
    @Test void closeReopenOrContactChangeBackCannotResurrectCancelledFutureAttempts() {
        task.setCancelRequestedAt(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now()));
        assertEquals("CANCEL_REQUESTED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        verifyNoInteractions(records); verify(deliveries,never()).updateById(any());
    }
    @Test void staleContactTokenChannelDisabledAndExpiredPeriodRejectBeforeNetwork() {
        when(readiness.hash(any(),eq("EMAIL"))).thenReturn("other"); assertEquals("CONTACT_CHANGED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        when(readiness.hash(any(),eq("EMAIL"))).thenReturn("hash"); when(records.lockAttempt(anyString(),anyString(),anyLong(),any())).thenReturn(false);
        assertEquals("SEND_ATTEMPT_CHANGED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        config.setDailyEnabled(false); assertEquals("PERIOD_DISABLED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        config.setDailyEnabled(true); event.setPeriodEnd(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now()));
        assertEquals("PERIOD_EXPIRED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
    }

    @Test void rolloutDisabledAndMissingAttemptOrDeliveryNeverGrantPermit() {
        assertNull(guard.verify("RESET_PASSWORD","r",attempt));
        properties.setDeliveryEnabled(false); assertEquals("PERSONAL_DELIVERY_DISABLED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        properties.setDeliveryEnabled(true); assertEquals("PERSONAL_DELIVERY_DISABLED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",null).errorCode());
        when(deliveries.selectOne(any())).thenReturn(null); assertEquals("DELIVERY_MISSING",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        when(deliveries.selectOne(any())).thenReturn(task,null); assertEquals("DELIVERY_CANCELLED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        when(deliveries.selectOne(any())).thenReturn(task); task.setStatus("ACCEPTED"); assertEquals("DELIVERY_CANCELLED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        verifyNoInteractions(records); verify(deliveries,never()).updateById(any());
    }

    @Test void channelOrRecipientMismatchAndUnavailableContactCannotObtainCurrentAttemptPermit() {
        var smsAttempt=new SendAttemptContext(MessageChannel.SMS,8L,"attempt-1",1,"hash");
        assertEquals("CONTACT_CHANGED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",smsAttempt).errorCode());
        task.setContactHash(null); assertEquals("CONTACT_CHANGED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode()); task.setContactHash("hash");
        var staleRecipient=new SendAttemptContext(MessageChannel.EMAIL,8L,"attempt-1",1,"old");
        assertEquals("CONTACT_CHANGED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",staleRecipient).errorCode());
        when(readiness.readiness(any())).thenReturn(List.of(new ChannelReadiness("IN_APP",true,null,null),new ChannelReadiness("EMAIL",false,"CONTACT_MISSING",null)));
        assertEquals("CHANNEL_UNAVAILABLE",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        verifyNoInteractions(records); verify(deliveries,never()).updateById(any());
    }

    @Test void missingConfigurationAndRemovedCurrentOrCapturedChannelsInvalidateWeeklyAlert() {
        when(configs.selectById(1L)).thenReturn(null); assertEquals("STATE_MISSING",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        when(configs.selectById(1L)).thenReturn(config); when(events.selectById("e")).thenReturn(null);
        assertEquals("STATE_MISSING",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        when(events.selectById("e")).thenReturn(event); event.setPeriodType("WEEK"); config.setWeeklyEnabled(false);
        assertEquals("PERIOD_DISABLED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode()); config.setWeeklyEnabled(true);
        config.setChannelsJson("[\"IN_APP\"]"); assertEquals("CHANNEL_REMOVED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        config.setChannelsJson("[\"EMAIL\"]"); event.setChannelsJson("[\"IN_APP\"]"); assertEquals("CHANNEL_REMOVED",guard.verify(UserCostAlertSendEligibility.SCENE,"r",attempt).errorCode());
        verifyNoInteractions(records); verify(deliveries,never()).updateById(any());
    }
}
