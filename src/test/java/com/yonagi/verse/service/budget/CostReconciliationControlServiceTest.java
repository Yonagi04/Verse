package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.event.CostReconciliationControlEvent;
import com.yonagi.verse.async.outbox.DomainEventPayload;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CostReconciliationControlServiceTest {
    static Stream<Consumer<CostReconciliationControlEvent>> invalidControls() {
        return Stream.of(e->e.setSchemaVersion(null),e->e.setSchemaVersion(2),e->e.setRunId(null),e->e.setRunId(0L),
                e->e.setKind(null),e->e.setSequence(null),e->e.setNotificationEpoch(null),
                e->e.setAfterBatchId(null),e->e.setAfterBatchId(-1L),e->e.setBatchId(7L),
                e->e.setKind(CostReconciliationControlEvent.Kind.CHECK_BATCH_NOTIFICATION));
    }

    @ParameterizedTest
    @MethodSource("invalidControls")
    void malformedSystemControlCannotAdvanceRun(Consumer<CostReconciliationControlEvent> change) {
        var event=event(CostReconciliationControlEvent.Kind.CHECK);change.accept(event);
        assertThrows(IllegalArgumentException.class,()->service.consume(event));
        assertEquals(0L,run.getControlSequence());
        verifyNoInteractions(coordinator,notifications,taskService);
    }
    private final CostReconciliationScanCoordinator coordinator = mock(CostReconciliationScanCoordinator.class);
    private final CostReconciliationScanMapper scans = mock(CostReconciliationScanMapper.class);
    private final CostReconciliationTaskMapper tasks = mock(CostReconciliationTaskMapper.class);
    private final CostReconciliationTaskService taskService = mock(CostReconciliationTaskService.class);
    private final CostReconciliationBatchMapper batches = mock(CostReconciliationBatchMapper.class);
    private final CostReconciliationNotifications notifications = mock(CostReconciliationNotifications.class);
    private final CostReconciliationSignals signals = mock(CostReconciliationSignals.class);
    private final DomainEventOutboxMapper outbox = mock(DomainEventOutboxMapper.class);
    private final CostReconciliationControlService service = new CostReconciliationControlService(coordinator,
            scans, tasks, taskService, batches, notifications, signals, outbox, mock(PlatformTransactionManager.class));
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 14, 8, 0);
    private CostReconciliationScanDO run;

    @BeforeEach
    void setup() {
        run = new CostReconciliationScanDO();
        run.setId(3L); run.setState("SEALED"); run.setNotificationEpoch(0L);
        run.setControlSequence(0L); run.setExecutionDeadlineAt(now.plusDays(1));
        run.setNotificationRecoveryRequired(true);
        when(scans.selectById(3L)).thenReturn(run);
        when(scans.lock(3L)).thenReturn(run);
        when(scans.databaseTime()).thenReturn(now);
    }

    @Test
    void oldNotificationSweepCannotEraseNewFailureEpoch() {
        var event = event(CostReconciliationControlEvent.Kind.RUN_SEALED);
        var batch = batch(7L);
        when(batches.runPage(3L, 0L)).thenReturn(List.of(batch));
        when(notifications.stageBatchIfReady(7L)).thenAnswer(call -> {
            run.setNotificationEpoch(1L); run.setNotificationRecoveryRequired(true); return true;
        });
        service.consume(event);
        assertTrue(run.getNotificationRecoveryRequired());
        assertEquals(1L, run.getNotificationEpoch());
    }

    @Test
    void currentSweepClearsFlagOnlyAfterLastBatchAndStagesReliableNextPage() {
        var event = event(CostReconciliationControlEvent.Kind.RUN_SEALED);
        when(batches.runPage(3L, 0L)).thenReturn(LongStream.rangeClosed(1, 100).mapToObj(this::batch).toList());
        when(notifications.stageBatchIfReady(anyLong())).thenReturn(true);
        service.consume(event);
        assertTrue(run.getNotificationRecoveryRequired());
        verify(signals).continueNotifications(eq(event), eq(100L), eq(now.plusDays(2)));
        when(batches.runPage(3L, 0L)).thenReturn(List.of());
        service.consume(event);
        assertFalse(run.getNotificationRecoveryRequired());
    }

    @Test
    void pausedOriginalBatchPersistsRecoveryEvenForOldSealedRun() {
        var event = event(CostReconciliationControlEvent.Kind.CHECK_BATCH_NOTIFICATION);
        event.setBatchId(7L); register(event);
        when(batches.selectById(7L)).thenReturn(batch(7L));
        service.consume(event);
        assertTrue(run.getNotificationRecoveryRequired());
        assertEquals(1L, run.getNotificationEpoch());
        verify(notifications).stageBatchIfReady(7L);
    }

    @Test
    void wrongOriginalBatchAndUnrecognizedPayloadAreRejected() {
        var event = event(CostReconciliationControlEvent.Kind.CHECK_BATCH_NOTIFICATION);
        event.setBatchId(7L); register(event);
        var other = batch(7L); other.setScanId(99L);
        when(batches.selectById(7L)).thenReturn(other);
        assertThrows(IllegalArgumentException.class, () -> service.consume(event));
        event.setRunId(99L);
        assertThrows(IllegalArgumentException.class, () -> service.consume(event));
        verifyNoInteractions(notifications);
    }

    @Test
    void dependencyFailureLeavesNoticeUnacknowledgedAndRecoveryFlagUntouched() {
        var event = event(CostReconciliationControlEvent.Kind.RUN_SEALED);
        when(batches.runPage(3L, 0L)).thenReturn(List.of(batch(7L)));
        when(notifications.stageBatchIfReady(7L)).thenThrow(new CostBudgetUnavailableException());
        assertThrows(CostBudgetUnavailableException.class, () -> service.consume(event));
        assertTrue(run.getNotificationRecoveryRequired());
        verify(outbox, never()).reconcileReconciliation(anyString(), any());
    }

    @Test
    void checksExpireOnlyCurrentRunAndStaleSequenceCannotExtendControlChain() {
        var event = event(CostReconciliationControlEvent.Kind.CHECK);
        run.setState("CLOSING"); run.setExecutionDeadlineAt(now.minusSeconds(1));
        when(tasks.pendingForExpiry(3L)).thenReturn(List.of("one", "two"));
        service.consume(event);
        verify(taskService).expire("one"); verify(taskService).expire("two");
        verify(coordinator).inspect(3L, 0L);
        reset(taskService); run.setControlSequence(1L);
        service.consume(event);
        verifyNoInteractions(taskService);
    }

    @Test
    void progressOnlyInspectsAndLegalTriggerRepairsExistingRunsWithoutDiscovery() {
        service.consume(event(CostReconciliationControlEvent.Kind.PROGRESS));
        verify(coordinator).inspect(3L, null);
        verifyNoInteractions(tasks, notifications);
        when(scans.recoveryPage(0L)).thenReturn(List.of(3L, 4L));
        when(scans.recoveryPage(4L)).thenReturn(List.of());
        service.recoverAtLegalTrigger();
        verify(coordinator).recover(3L); verify(coordinator).recover(4L);
    }

    @Test
    void malformedControlAndUnsealedNoticeFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> service.consume(new CostReconciliationControlEvent()));
        var event = event(CostReconciliationControlEvent.Kind.RUN_SEALED);
        run.setState("CLOSING");
        assertThrows(IllegalStateException.class, () -> service.consume(event));
        verifyNoInteractions(notifications);
    }

    @Test
    void pausedRunSweepPersistsRecoveryVersionAndStopsPaging() {
        var event=event(CostReconciliationControlEvent.Kind.RUN_SEALED);
        when(batches.runPage(3L,0L)).thenReturn(List.of(batch(7L),batch(8L)));
        service.consume(event);
        assertTrue(run.getNotificationRecoveryRequired());assertEquals(1L,run.getNotificationEpoch());
        verify(notifications,never()).stageBatchIfReady(8L);
        verifyNoInteractions(signals);
    }

    @Test
    void missingOrActivationRunCannotAcceptSystemControl() {
        var event=event(CostReconciliationControlEvent.Kind.PROGRESS);
        when(scans.selectById(3L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,()->service.consume(event));
        run.setState("ACTIVATION");when(scans.selectById(3L)).thenReturn(run);
        assertThrows(IllegalArgumentException.class,()->service.consume(event));
        verifyNoInteractions(coordinator,notifications);
    }

    @Test
    void oneOldRunFailureDoesNotBlockOtherRunRecoveryOrEscapeDailyTrigger() {
        when(scans.recoveryPage(0L)).thenReturn(List.of(3L,4L));
        when(scans.recoveryPage(4L)).thenReturn(List.of());
        doThrow(new CostBudgetUnavailableException()).when(coordinator).recover(3L);
        service.recoverAtLegalTrigger();
        verify(coordinator).recover(4L);
        assertTrue(run.getNotificationRecoveryRequired());
        assertEquals(0L,run.getControlSequence());
    }

    private CostReconciliationControlEvent event(CostReconciliationControlEvent.Kind kind) {
        var event = new CostReconciliationControlEvent();
        event.setEventId("control"); event.setOccurredAt(1L); event.setSchemaVersion(1);
        event.setRunId(3L); event.setKind(kind); event.setSequence(0L); event.setAfterBatchId(0L);
        event.setNotificationEpoch(0L);
        register(event);
        return event;
    }

    private void register(CostReconciliationControlEvent event) {
        var intent = new DomainEventOutboxDO();
        intent.setEventType(event.eventType()); intent.setPayloadHash(DomainEventPayload.hash(event));
        intent.setRetryUntil(now.plusDays(2));
        when(outbox.byEvent(event.getEventId())).thenReturn(intent);
    }

    private CostReconciliationBatchDO batch(Long id) {
        var batch = new CostReconciliationBatchDO(); batch.setId(id); batch.setScanId(3L); return batch;
    }
}
