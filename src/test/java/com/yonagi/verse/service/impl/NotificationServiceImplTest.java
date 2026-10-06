package com.yonagi.verse.service.impl;

import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.common.cache.QueryCache;
import com.yonagi.verse.dao.entity.NotificationDO;
import com.yonagi.verse.dao.entity.NotificationRecipientDO;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.NotificationRecipientMapper;
import com.yonagi.verse.service.notification.NotificationCreationService;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceImplTest {
    private NotificationMapper notifications;
    private NotificationRecipientMapper recipients;
    private SimpMessagingTemplate messaging;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationMapper.class);
        recipients = mock(NotificationRecipientMapper.class);
        messaging = mock(SimpMessagingTemplate.class);
        service = new NotificationServiceImpl(mock(TenantAccessPolicy.class), recipients,
                mock(DomainEventPublisher.class), mock(QueryCache.class),
                new NotificationCreationService(notifications, recipients, messaging));
        when(notifications.insert(any(NotificationDO.class))).thenReturn(1);
        when(recipients.insert(any(NotificationRecipientDO.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void recipientFailurePropagatesWithoutPushing() {
        when(recipients.insert(any(NotificationRecipientDO.class)))
                .thenThrow(new DataAccessResourceFailureException("recipient insert failed"));
        assertThrows(DataAccessResourceFailureException.class, this::create);
        verifyNoInteractions(messaging);
    }

    @Test
    void successfulCreationDoesNotPushBeforeCommit() {
        create();
        verify(notifications).insert(any(NotificationDO.class));
        verify(recipients).insert(any(NotificationRecipientDO.class));
        verifyNoInteractions(messaging);
    }

    private void create() {
        service.createAndPush(20L, "ANNOUNCEMENT", "INFO", "标题", "正文", 10L, List.of(30L));
    }
}
