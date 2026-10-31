package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.dao.entity.skillhub.SkillAccountCleanupDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillAccountCleanupMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublisherMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 只验证本域处置的业务状态，不模拟证明物理删除或Outbox原子提交。 */
class SkillAccountCleanupServiceTest {
    private final SkillPublisherMapper publishers = mock(SkillPublisherMapper.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final SkillImportTaskMapper tasks = mock(SkillImportTaskMapper.class);
    private final SkillAccountCleanupMapper progress = mock(SkillAccountCleanupMapper.class);
    private final ReliableDomainEventPublisher events = mock(ReliableDomainEventPublisher.class);
    private final SkillAccountCleanupService service = new SkillAccountCleanupService(publishers, drafts, tasks,
            progress, events);

    @BeforeEach
    void mapping() {
        SkillTestFixture.initializeMapping(SkillDraftDO.class, SkillImportTaskDO.class, SkillAccountCleanupDO.class);
    }

    @Test
    void freezeClosesPublisherAndCreatesRunnableObligationRatherThanClaimingDeletion() {
        SkillPublisherDO publisher = SkillTestFixture.publisher();
        when(publishers.selectByUserIdForUpdate(1L)).thenReturn(publisher);
        service.freeze(1L);
        ArgumentCaptor<SkillAccountCleanupDO> inserted = ArgumentCaptor.forClass(SkillAccountCleanupDO.class);
        verify(progress).insert(inserted.capture());
        assertEquals("CLOSED", publisher.getStatus());
        assertEquals(1L, publisher.getRevision());
        assertEquals("RUNNABLE", inserted.getValue().getState());
        assertEquals(1L, inserted.getValue().getUserId());
        verify(events, never()).publish(any(), any());
    }

    @Test
    void waitingForRetentionPolicyNeverMeansCompleteAndNeverCreatesContinuationLoop() {
        when(progress.update(any(), any())).thenReturn(1);
        service.settle(1L, true);
        verify(events, never()).publish(any(), any());
        SkillAccountCleanupDO waiting = new SkillAccountCleanupDO();
        waiting.setState("WAITING_RETENTION_POLICY");
        when(progress.selectById(1L)).thenReturn(waiting);
        assertTrue(service.isWaitingForRetentionPolicy(1L));
        assertFalse(service.isComplete(1L));
    }

    @Test
    void actualCompletionWakesOriginalCleanupOnlyForSuccessfulStateTransition() {
        when(progress.update(any(), any())).thenReturn(1, 0);
        service.settle(1L, false);
        service.settle(1L, false);
        verify(events).publish(any(), eq(0L));
        SkillAccountCleanupDO completed = new SkillAccountCleanupDO();
        completed.setState("COMPLETED");
        when(progress.selectById(1L)).thenReturn(completed);
        assertTrue(service.isComplete(1L));
        assertFalse(service.isWaitingForRetentionPolicy(1L));
    }

    @Test
    void accountWithoutPublisherHasNoSkillObligation() {
        assertTrue(service.isComplete(1L));
        verify(progress, never()).insert(any(SkillAccountCleanupDO.class));
        verify(drafts, never()).update(any(), any());
    }
}
