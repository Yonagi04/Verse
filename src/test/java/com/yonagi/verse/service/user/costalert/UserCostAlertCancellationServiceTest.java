package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.support.UserUsageTestFixtures;
import com.yonagi.verse.common.enums.MessageChannel;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.messaging.SendRecordStore;
import org.junit.jupiter.api.*;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCostAlertCancellationServiceTest {
    final UserCostAlertDeliveryMapper tasks=mock(UserCostAlertDeliveryMapper.class);
    final UserCostAlertEventMapper events=mock(UserCostAlertEventMapper.class);
    final SendRecordStore records=mock(SendRecordStore.class);
    final UserCostAlertCancellationService service=new UserCostAlertCancellationService(tasks,events,records,UserUsageTestFixtures.TIME);
    UserCostAlertDeliveryDO task; Set<Object> written;
    @BeforeEach void setup() {
        UserUsageTestFixtures.tables(); task=UserUsageTestFixtures.task(); when(events.selectList(any())).thenReturn(List.of(UserUsageTestFixtures.event())); when(tasks.selectList(any())).thenReturn(List.of(task));
        when(records.findSubmission(MessageChannel.EMAIL,"r")).thenReturn(Optional.empty());
        doAnswer(invocation->{ LambdaUpdateWrapper<?> update=invocation.getArgument(0); update.getSqlSet(); update.getSqlSegment(); written=new HashSet<>(update.getParamNameValuePairs().values()); return 1; }).when(tasks).update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
    @Test void contactChangePermanentlyCancelsUnpermittedQueueButUnchangedContactDoesNotWrite() {
        service.contactsChanged(1L,false,false); verifyNoInteractions(events,tasks,records);
        service.contactsChanged(1L,false,true); assertTrue(written.contains("CANCELLED")); assertTrue(written.contains("CANCEL_REQUESTED"));
        assertTrue(written.contains(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now())));
    }
    @Test void currentPermittedAttemptCanFinishButFutureRetriesAreMarkedCancelled() {
        task.setPermittedSendToken("attempt1"); when(records.findSubmission(MessageChannel.EMAIL,"r")).thenReturn(Optional.of(new SendRecordStore.SubmissionSnapshot("SUBMITTING",null,"hash","attempt1")));
        service.contactsChanged(1L,false,true); assertTrue(written.contains("QUEUED")); assertTrue(written.contains("CANCEL_REQUESTED"));
        when(records.findSubmission(MessageChannel.EMAIL,"r")).thenReturn(Optional.of(new SendRecordStore.SubmissionSnapshot("ACCEPTED",null,"hash",null)));
        service.contactsChanged(1L,false,true); assertTrue(written.contains("ACCEPTED")); assertFalse(written.contains("CANCELLED"));
    }
    @Test void removedChannelOrDisabledPeriodCancelsAndRetainedConfigurationLeavesQueueAlone() {
        when(events.selectById("e")).thenReturn(UserUsageTestFixtures.event()); var config=UserUsageTestFixtures.config();
        service.configurationChanged(1L,config); verify(tasks,never()).update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        config.setChannelsJson("[\"IN_APP\"]"); service.configurationChanged(1L,config); assertTrue(written.contains("CANCELLED"));
        config.setChannelsJson("[\"EMAIL\"]"); config.setDailyEnabled(false); service.configurationChanged(1L,config); assertTrue(written.contains("CANCELLED"));
    }

    @Test void phoneChangeOnlyCancelsSmsAndSubmittingWithoutMatchingPermitIsCancelled() {
        service.contactsChanged(1L,true,false); assertNull(written); verifyNoInteractions(records);
        task.setChannel("SMS"); when(records.findSubmission(MessageChannel.SMS,"r")).thenReturn(Optional.of(new SendRecordStore.SubmissionSnapshot("SUBMITTING",null,"hash","other")));
        service.contactsChanged(1L,true,false); assertTrue(written.contains("CANCELLED"));
        task.setPermittedSendToken("old-permit"); service.contactsChanged(1L,true,false); assertTrue(written.contains("CANCELLED"));
        when(records.findSubmission(MessageChannel.SMS,"r")).thenReturn(Optional.of(new SendRecordStore.SubmissionSnapshot("QUEUED",null,"hash",null)));
        service.contactsChanged(1L,true,false); assertTrue(written.contains("CANCELLED"));
    }

    @Test void disabledWeeklyOrMissingEventCancelsInAppWithoutConsultingProvider() {
        task.setChannel("IN_APP"); var config=UserUsageTestFixtures.config(); config.setChannelsJson("[\"IN_APP\"]"); config.setWeeklyEnabled(false);
        var event=UserUsageTestFixtures.event(); event.setPeriodType("WEEK"); when(events.selectById("e")).thenReturn(event);
        service.configurationChanged(1L,config); assertTrue(written.contains("CANCELLED")); verifyNoInteractions(records);
        written=null; when(events.selectById("e")).thenReturn(null); service.configurationChanged(1L,config); assertTrue(written.contains("CANCEL_REQUESTED"));
        clearInvocations(tasks); when(events.selectList(any())).thenReturn(List.of()); service.configurationChanged(1L,config);
        verifyNoInteractions(tasks);
    }
}
