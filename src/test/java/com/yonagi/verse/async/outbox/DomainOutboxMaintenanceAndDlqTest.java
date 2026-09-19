package com.yonagi.verse.async.outbox;

import com.yonagi.verse.async.mq.TenantActivityDlqObserver;
import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainOutboxMaintenanceAndDlqTest {

    @Test
    void maintenanceOnlyCleansPublishedAndReconciledRows() throws Exception {
        Method cleanup = DomainEventOutboxMapper.class.getMethod(
                "deleteReconciledBefore", LocalDateTime.class);
        String cleanupSql = String.join(" ", cleanup.getAnnotation(Delete.class).value());
        assertTrue(cleanupSql.contains("status='PUBLISHED'"));
        assertTrue(cleanupSql.contains("reconciled_at IS NOT NULL"));

        Method reconcile = DomainEventOutboxMapper.class.getMethod(
                "reconcilePublished", LocalDateTime.class);
        String reconcileSql = String.join(" ", reconcile.getAnnotation(Update.class).value());
        assertTrue(reconcileSql.contains("o.status='PUBLISHED'"));
        assertTrue(reconcileSql.contains("t_tenant_activity_log"));

        DomainEventOutboxMapper mapper = mock(DomainEventOutboxMapper.class);
        when(mapper.reconcilePublished(any())).thenReturn(2);
        when(mapper.deleteReconciledBefore(any())).thenReturn(1);
        DomainOutboxProperties properties = new DomainOutboxProperties();
        properties.setReconciledRetentionDays(7);
        new DomainOutboxMaintenanceJob(mapper, properties, mock(DomainOutboxMetrics.class))
                .reconcileAndCleanup();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).deleteReconciledBefore(cutoff.capture());
        LocalDateTime expected = LocalDateTime.now().minusDays(7);
        assertTrue(Math.abs(java.time.Duration.between(expected, cutoff.getValue()).toSeconds()) < 2);
    }

    @Test
    void deadLetterUsesMessageKeyAsEventIdWithoutReadingPayload() {
        DomainEventOutboxMapper mapper = mock(DomainEventOutboxMapper.class);
        TenantActivityDlqObserver observer = new TenantActivityDlqObserver(mapper);
        MessageExt message = new MessageExt();
        message.setKeys("event-1");
        message.setMsgId("msg-1");

        observer.onMessage(message);

        verify(mapper).markConsumerDlq(eq("event-1"), contains("msgId=msg-1"), any());
        MessageExt missingKey = new MessageExt();
        missingKey.setMsgId("msg-2");
        assertThrows(IllegalArgumentException.class, () -> observer.onMessage(missingKey));
    }
}
