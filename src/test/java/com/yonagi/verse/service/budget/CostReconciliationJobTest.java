package com.yonagi.verse.service.budget;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CostReconciliationJobTest {

    @Test
    void rejectedTriggerReleasesLocalSlotAndDisabledActivationDoesNotDiscover() {
        List<Runnable> pending=new ArrayList<>();
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        TaskExecutor executor=task->{
            if(attempts.getAndIncrement()==0) {
                throw new org.springframework.core.task.TaskRejectedException("busy");
            }
            pending.add(task);
        };
        var tracking=mock(CostReconciliationTracking.class);var producer=mock(CostReconciliationProducer.class);
        var controls=mock(CostReconciliationControlService.class);
        var job=new CostReconciliationJob(executor,tracking,producer,controls);
        ReflectionTestUtils.setField(job,"processingEnabled",true);
        job.applicationReady();job.applicationReady();
        assertEquals(1,pending.size());pending.getFirst().run();
        verifyNoInteractions(producer,controls);
    }

    @Test
    void dailyTriggerSurvivesBootDiscoveryOccupyingOtherSlot() {
        List<Runnable> pending = new ArrayList<>();
        TaskExecutor executor = pending::add;
        var tracking = mock(CostReconciliationTracking.class);
        var producer = mock(CostReconciliationProducer.class);
        var controls = mock(CostReconciliationControlService.class);
        when(tracking.enabled()).thenReturn(true);
        var job = new CostReconciliationJob(executor, tracking, producer, controls);
        ReflectionTestUtils.setField(job, "processingEnabled", true);
        job.daily();
        assertTrue(pending.isEmpty());
        job.applicationReady();
        job.daily(); job.daily();
        assertEquals(2, pending.size());
        pending.forEach(Runnable::run);
        verify(producer).discover(startsWith("BOOT:"), eq(false));
        verify(producer).discover(startsWith("DAILY:"), eq(true));
    }
}
