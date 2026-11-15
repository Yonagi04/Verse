package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.common.config.CostReconciliationMqProperties;
import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import com.yonagi.verse.dao.entity.CostReconciliationTaskDO;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证运行决策与发布内容，不以 Mock 证明数据库锁或原子提交。 */
class CostReconciliationScanCoordinatorTest {
    static Stream<Consumer<CostReconciliationScanDO>> closedProducerFences() {
        return Stream.of(r->r.setState("CLOSING"),r->r.setProductionClosed(true),r->r.setProducerToken("other"),
                r->r.setProducerLeaseUntil(LocalDateTime.MIN),r->r.setDiscoveryDeadlineAt(LocalDateTime.MIN));
    }

    @ParameterizedTest
    @MethodSource("closedProducerFences")
    void everyProducerFenceRejectsAdditionalPage(Consumer<CostReconciliationScanDO> change) {
        change.accept(run);
        assertFalse(service.append(3L,"token",page(),now,11L,0));
        assertEquals(0L,run.getTaskCount());verifyNoInteractions(events);
    }
    private final CostReconciliationScanMapper scans = mock(CostReconciliationScanMapper.class);
    private final CostReconciliationTaskMapper tasks = mock(CostReconciliationTaskMapper.class);
    private final CostReconciliationBatchMapper batches = mock(CostReconciliationBatchMapper.class);
    private final ReliableDomainEventPublisher events = mock(ReliableDomainEventPublisher.class);
    private final CostReconciliationSignals signals = mock(CostReconciliationSignals.class);
    private final CostReconciliationMqProperties properties = new CostReconciliationMqProperties();
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 14, 8, 0);
    private final CostReconciliationScanCoordinator service =
            new CostReconciliationScanCoordinator(scans, tasks, batches, events, signals, properties);
    private CostReconciliationScanDO run;

    @BeforeEach
    void setup() {
        properties.setBrokerPreflightVerified(true);
        when(scans.databaseTime()).thenReturn(now);
        var marker = new CostReconciliationScanDO();
        marker.setGeneration(2L);
        when(scans.lockActivation()).thenReturn(marker);
        run = run();
        when(scans.lock(3L)).thenReturn(run);
        doAnswer(call -> { call.getArgument(0, CostReconciliationScanDO.class).setId(9L); return 1; })
                .when(scans).insert(any(CostReconciliationScanDO.class));
    }

    private CostReconciliationScanDO run() {
        var value = new CostReconciliationScanDO();
        value.setId(3L); value.setState("OPEN"); value.setGeneration(2L); value.setTriggerKey("BOOT:old");
        value.setCutoffAt(now.minusMinutes(5)); value.setDiscoveryDeadlineAt(now.plusMinutes(5));
        value.setExecutionDeadlineAt(now.plusHours(23)); value.setCleanupDeadlineAt(now.plusHours(47));
        value.setProducerToken("token"); value.setProducerLeaseUntil(now.plusSeconds(60));
        value.setTaskCount(0L); value.setProductionClosed(false); value.setDiscoveryComplete(false);
        value.setControlSequence(0L); value.setNotificationEpoch(0L);
        return value;
    }

    @Test
    void dailyFreezesBootAndStartsUniqueRunWithFixedDeadlines() {
        when(scans.lockActiveScan()).thenReturn(run);
        var created = service.begin("DAILY:2026-11-14", "daily", true);
        assertEquals("CLOSING", run.getState());
        assertTrue(run.getProductionClosed());
        assertFalse(run.getDiscoveryComplete());
        assertEquals(9L, created.getId());
        assertEquals(now, created.getCutoffAt());
        assertEquals(now.plusMinutes(10), created.getDiscoveryDeadlineAt());
        assertEquals(now.plusHours(24), created.getExecutionDeadlineAt());
        assertEquals(now.plusHours(48), created.getCleanupDeadlineAt());
        verify(signals).check(created, now, 14);
    }

    @Test
    void activeBootIsMergedAndExpiredProducerTakeoverDoesNotExtendWindow() {
        when(scans.lockActiveScan()).thenReturn(run);
        assertNull(service.begin("BOOT:new", "new", false));
        run.setProducerLeaseUntil(now);
        assertSame(run, service.begin("BOOT:new", "new", false));
        assertEquals("new", run.getProducerToken());
        assertEquals(now.plusMinutes(5), run.getDiscoveryDeadlineAt());
    }

    @Test
    void triggerAlreadyClosedAndDisabledActivationDoNotCreateAnotherRun() {
        when(scans.byTrigger("DAILY:date")).thenReturn(run);
        assertNull(service.begin("DAILY:date", "new", true));
        when(scans.lockActivation()).thenReturn(null);
        assertNull(service.begin("BOOT:new", "new", false));
        verify(scans, never()).insert(any(CostReconciliationScanDO.class));
    }

    @Test
    void preflightAndProducerFencesRejectUnsafeDiscovery() {
        properties.setBrokerPreflightVerified(false);
        assertThrows(IllegalStateException.class, () -> service.begin("BOOT:new", "new", false));
        assertFalse(service.append(3L, "stale", page(), now, 11L, 0));
        run.setProducerLeaseUntil(now);
        assertFalse(service.append(3L, "token", page(), now, 11L, 0));
        verifyNoInteractions(events);
    }

    @Test
    void pageCheckpointCapturesFactsAndRejectsConflictingSequence() {
        var page = page();
        assertTrue(service.append(3L, "token", page, now, 11L, 0));
        assertEquals(1L, run.getTaskCount());
        var captured = ArgumentCaptor.forClass(CostReconciliationTaskDO.class);
        verify(tasks).insert(captured.capture());
        assertEquals(2, captured.getValue().getItemCount());
        assertEquals(11L, captured.getValue().getCursorId());
        assertEquals(run.getExecutionDeadlineAt(), captured.getValue().getExecutionDeadlineAt());
        assertThrows(IllegalStateException.class, () -> service.append(3L, "token", page, now, 11L, 0));
    }

    @Test
    void duplicateCommittedPageIsIdempotentButDifferentHashIsRejected() {
        var event = page();
        var committed = new CostReconciliationTaskDO();
        committed.setTaskId(event.getTaskId()); committed.setScanId(3L); committed.setTenantId(7L);
        committed.setOperation(event.getOperation().name()); committed.setPayloadHash(event.getPayloadHash());
        run.setTaskCount(5L);
        when(tasks.bySequence(3L, 0L)).thenReturn(committed);
        assertTrue(service.append(3L, "token", event, now, 11L, 0));
        event.setPayloadHash("changed");
        assertThrows(IllegalArgumentException.class, () -> service.append(3L, "token", event, now, 11L, 0));
        assertEquals(5L, run.getTaskCount());
    }

    @Test
    void pendingOrOpenRunCannotSealAndCountMismatchFailsClosed() {
        service.inspect(3L, null);
        assertEquals("OPEN", run.getState());
        service.close(3L, "token", true);
        when(tasks.hasPending(3L)).thenReturn(true);
        service.inspect(3L, null);
        assertEquals("CLOSING", run.getState());
        verify(tasks, never()).countAll(3L);
        when(tasks.hasPending(3L)).thenReturn(false);
        when(tasks.countAll(3L)).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> service.inspect(3L, null));
        assertEquals("CLOSING", run.getState());
    }

    @Test
    void allTerminalPagesSealWithVisibleFailureAndPersistentNotificationDeadline() {
        service.close(3L, "token", true);
        service.inspect(3L, null);
        assertEquals("SEALED", run.getState());
        assertEquals("COMPLETE", run.getCompletionReason());
        assertEquals(now.plusHours(48), run.getNotificationDeadlineAt());
        verify(signals).sealed(run, 0);
        reset(tasks);
        service.inspect(3L, null);
        verifyNoInteractions(tasks);
        run.setState("CLOSING"); run.setTaskCount(2L);
        when(tasks.countAll(3L)).thenReturn(2L); when(tasks.countUnsuccessful(3L)).thenReturn(2L);
        service.inspect(3L, null);
        assertEquals("FAILED", run.getCompletionReason());
    }


    @Test
    void partialDiscoveryAndControlSequenceAdvanceOnlyOnce() {
        service.close(3L, "stale", true);
        assertEquals("OPEN", run.getState());
        when(tasks.hasPending(3L)).thenReturn(true);
        service.inspect(3L, 0L); service.inspect(3L, 0L);
        assertEquals(1L, run.getControlSequence());
        verify(signals).check(run, now, 18);
        run.setDiscoveryDeadlineAt(now);
        service.inspect(3L, null);
        assertEquals("CLOSING", run.getState());
        assertFalse(run.getDiscoveryComplete());
    }

    @Test
    void expiredControlGraceLeavesVisibleRecoveryAndLegalRepairPreservesBusinessDeadline() {
        run.setCleanupDeadlineAt(now); when(tasks.hasPending(3L)).thenReturn(true);
        service.inspect(3L, 0L);
        assertTrue(run.getControlRecoveryRequired());
        var originalExecution = run.getExecutionDeadlineAt();
        run.setProducerLeaseUntil(now);
        service.recover(3L);
        assertEquals("CLOSING", run.getState());
        assertEquals(originalExecution, run.getExecutionDeadlineAt());
        assertFalse(run.getControlRecoveryRequired());
        assertEquals(2L, run.getControlSequence());
    }

    @Test
    void expiredBootCannotExtendDiscoveryAndInactiveMarkerCreatesNothing() {
        when(scans.lockActiveScan()).thenReturn(run);run.setDiscoveryDeadlineAt(now);
        var created=service.begin("BOOT:new","new",false);
        assertEquals("CLOSING",run.getState());assertEquals(now,created.getCutoffAt());
        assertEquals("BOOT:new",created.getTriggerKey());
        var marker=new CostReconciliationScanDO();marker.setGeneration(0L);
        when(scans.lockActivation()).thenReturn(marker);
        assertNull(service.begin("BOOT:disabled","new",false));
    }

    @Test
    void partialManifestAndExpiredExecutionRemainVisibleAndContinueImmediateCleanup() {
        service.close(3L,"token",false);run.setTaskCount(2L);
        when(tasks.countAll(3L)).thenReturn(2L);when(tasks.countUnsuccessful(3L)).thenReturn(1L);
        service.inspect(3L,null);
        assertEquals("PARTIAL",run.getCompletionReason());
        run.setState("CLOSING");run.setExecutionDeadlineAt(now);when(tasks.hasPending(3L)).thenReturn(true);
        service.inspect(3L,0L);
        assertEquals(1L,run.getControlSequence());verify(signals).check(run,now,0);
    }

    @Test
    void sealedRecoveryUsesNewNoticeIdentityAndInvalidRunCannotBeInspected() {
        run.setState("SEALED");run.setSealedAt(now);run.setNotificationDeadlineAt(now.plusDays(2));
        run.setControlRecoveryRequired(true);
        service.recover(3L);
        assertEquals(1L,run.getControlSequence());assertFalse(run.getControlRecoveryRequired());
        verify(signals).recoverNotifications(run,now);
        when(scans.lock(3L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,()->service.inspect(3L,null));
        run.setState("ACTIVATION");when(scans.lock(3L)).thenReturn(run);
        assertThrows(IllegalArgumentException.class,()->service.inspect(3L,null));
    }

    private CostReconciliationTaskEvent page() {
        var event = new CostReconciliationTaskEvent();
        event.setTaskId("crt-3-0"); event.setRunId(3L); event.setGeneration(2L); event.setTenantId(7L);
        event.setOperation(CostReconciliationTaskEvent.Operation.CLASSIFY_REQUESTS);
        event.setRequestIds(List.of("one", "two")); event.setPayloadHash("hash");
        return event;
    }
}
