package com.yonagi.verse.service.notification;

import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.async.handler.NotificationEventHandler;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.NotificationErrorCodeEnum;
import com.yonagi.verse.dao.entity.NotificationDO;
import com.yonagi.verse.dao.entity.NotificationRecipientDO;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.NotificationRecipientMapper;
import com.yonagi.verse.dto.resp.NotificationInfoRespDTO;
import com.yonagi.verse.dto.resp.NotificationUnreadCountRespDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationCreationServiceTest {
    private NotificationMapper notifications;
    private NotificationRecipientMapper recipients;
    private SimpMessagingTemplate messaging;
    private NotificationCreationService service;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationMapper.class);
        recipients = mock(NotificationRecipientMapper.class);
        messaging = mock(SimpMessagingTemplate.class);
        service = new NotificationCreationService(notifications, recipients, messaging);
        when(notifications.insert(any(NotificationDO.class))).thenAnswer(call -> {
            ((NotificationDO) call.getArgument(0)).setCreateTime(new Date(123456L));
            return 1;
        });
        when(recipients.insert(any(NotificationRecipientDO.class))).thenReturn(1);
        when(recipients.selectCount(any())).thenReturn(7L);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void commitPushesPayloadAndUnreadCountFromRecipientSnapshot() {
        NotificationEvent event = event();
        event.setRecipientUserIds(new ArrayList<>(List.of(30L, 40L)));
        service.createAndPush(event);
        event.setTitle("后续修改");
        event.getRecipientUserIds().clear();
        verifyNoInteractions(messaging);
        commit();

        ArgumentCaptor<NotificationInfoRespDTO> payload = ArgumentCaptor.forClass(NotificationInfoRespDTO.class);
        verify(messaging).convertAndSendToUser(eq("30"), eq("/queue/notifications"), payload.capture());
        assertEquals(100L, payload.getValue().getNotificationId());
        assertEquals("标题", payload.getValue().getTitle());
        assertEquals("正文", payload.getValue().getContent());
        assertEquals("SYSTEM", payload.getValue().getType());
        assertEquals("INFO", payload.getValue().getSeverity());
        assertEquals(10L, payload.getValue().getSenderId());
        assertEquals(new Date(123456L), payload.getValue().getCreateTime());
        ArgumentCaptor<NotificationUnreadCountRespDTO> count = ArgumentCaptor.forClass(NotificationUnreadCountRespDTO.class);
        verify(messaging).convertAndSendToUser(eq("30"), eq("/queue/notifications/unread-count"), count.capture());
        assertEquals(7L, count.getValue().getCount());
        verify(messaging).convertAndSendToUser(eq("40"), eq("/queue/notifications"), any(NotificationInfoRespDTO.class));
        verify(messaging).convertAndSendToUser(eq("40"), eq("/queue/notifications/unread-count"), any(NotificationUnreadCountRespDTO.class));
    }

    @Test
    void zeroNotificationRowsFailsWithoutWritingRecipientsOrSchedulingPush() {
        when(notifications.insert(any(NotificationDO.class))).thenReturn(0);
        ServerException failure = assertThrows(ServerException.class, () -> service.createAndPush(event()));
        assertEquals(NotificationErrorCodeEnum.NOTIFICATION_CREATE_FAILED.code(), failure.getErrorCode());
        verifyNoInteractions(recipients, messaging);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void zeroRecipientRowsFailsWithoutSchedulingPush() {
        when(recipients.insert(any(NotificationRecipientDO.class))).thenReturn(0);
        assertThrows(ServerException.class, () -> service.createAndPush(event()));
        verifyNoInteractions(messaging);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void confirmedDuplicateEventDoesNotWriteRecipientsOrPushAgain() {
        when(notifications.insert(any(NotificationDO.class))).thenThrow(new DuplicateKeyException("duplicate id"));
        when(notifications.selectCount(any())).thenReturn(1L);
        assertDoesNotThrow(() -> new NotificationEventHandler(service).onEvent(event()));
        verifyNoInteractions(recipients, messaging);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void unknownDuplicateAndSynchronousDuplicatePropagate() {
        when(notifications.insert(any(NotificationDO.class))).thenThrow(new DuplicateKeyException("unknown key"));
        when(notifications.selectCount(any())).thenReturn(0L);
        assertThrows(DuplicateKeyException.class, () -> service.createAndPushIfAbsent(event()));
        when(notifications.selectCount(any())).thenReturn(1L);
        assertThrows(DuplicateKeyException.class, () -> service.createAndPush(event()));
        verifyNoInteractions(recipients, messaging);
    }

    @Test
    void recipientDuplicateInConsumerPropagatesInsteadOfBeingAcknowledged() {
        when(recipients.insert(any(NotificationRecipientDO.class))).thenThrow(new DuplicateKeyException("recipient key"));
        assertThrows(DuplicateKeyException.class, () -> new NotificationEventHandler(service).onEvent(event()));
        verify(notifications, never()).selectCount(any());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void pushFailureDoesNotFailCommitOrPreventOtherRecipients() {
        NotificationEvent event = event();
        event.setRecipientUserIds(List.of(30L, 40L));
        doThrow(new IllegalStateException("websocket unavailable")).when(messaging)
                .convertAndSendToUser(eq("30"), eq("/queue/notifications"), any());
        service.createAndPush(event);
        assertDoesNotThrow(this::commit);
        verify(messaging).convertAndSendToUser(eq("40"), eq("/queue/notifications"), any());
        verify(messaging).convertAndSendToUser(eq("40"), eq("/queue/notifications/unread-count"), any());
    }

    @Test
    void emptyRecipientsPreservesCreationWithoutPush() {
        NotificationEvent event = event();
        event.setRecipientUserIds(List.of());
        service.createAndPush(event);
        commit();
        verify(notifications).insert(any(NotificationDO.class));
        verifyNoInteractions(recipients, messaging);
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setNotificationId(100L);
        event.setTenantId(20L);
        event.setType("SYSTEM");
        event.setSeverity("INFO");
        event.setTitle("标题");
        event.setContent("正文");
        event.setSenderId(10L);
        event.setRecipientUserIds(List.of(30L));
        return event;
    }
}
