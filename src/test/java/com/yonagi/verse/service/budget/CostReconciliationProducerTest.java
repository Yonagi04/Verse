package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import com.yonagi.verse.dao.entity.CostReconciliationTaskDO;
import com.yonagi.verse.dao.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只验证候选分页编排、公平与边界，不把内存游标替身当成数据库可靠性证明。 */
class CostReconciliationProducerTest {
    private final CostReconciliationScanCoordinator coordinator = mock(CostReconciliationScanCoordinator.class);
    private final CostReconciliationScanMapper scans = mock(CostReconciliationScanMapper.class);
    private final CostReconciliationTaskMapper tasks = mock(CostReconciliationTaskMapper.class);
    private final CostBudgetInvocationMapper invocations = mock(CostBudgetInvocationMapper.class);
    private final CostReconciliationCaseMapper cases = mock(CostReconciliationCaseMapper.class);
    private final CostReconciliationProducer service = new CostReconciliationProducer(coordinator, scans, tasks, invocations, cases);
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 14, 8, 0);
    private final List<CostReconciliationTaskEvent> produced = new ArrayList<>();
    private final Map<String, CostReconciliationTaskDO> cursors = new HashMap<>();
    private CostReconciliationScanDO run;

    @BeforeEach
    void setup() {
        run = new CostReconciliationScanDO(); run.setId(3L); run.setGeneration(2L); run.setState("OPEN");
        run.setTaskCount(0L); run.setCutoffAt(now); run.setDiscoveryDeadlineAt(now.plusMinutes(10));
        run.setProducerLeaseUntil(now.plusMinutes(1));
        when(coordinator.begin(eq("DAILY:date"), anyString(), eq(true))).thenAnswer(call -> {
            run.setProducerToken(call.getArgument(1)); return run;
        });
        when(scans.selectById(3L)).thenReturn(run); when(scans.databaseTime()).thenReturn(now);
        when(invocations.nextCandidateTenant(anyLong(), anyLong(), any())).thenReturn(null);
        when(cases.nextExpiredTenant(anyLong(), any())).thenReturn(null);
        when(tasks.lastPage(eq(3L), anyLong(), anyString())).thenAnswer(call ->
                cursors.get(call.getArgument(1) + ":" + call.getArgument(2)));
        when(coordinator.append(eq(3L), anyString(), any(), nullable(LocalDateTime.class), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    CostReconciliationTaskEvent event = call.getArgument(2);
                    CostReconciliationTaskProtocol.validate(event);
                    var cursor = new CostReconciliationTaskDO();
                    cursor.setCursorAt(call.getArgument(3)); cursor.setCursorId(call.getArgument(4));
                    cursors.put(event.getTenantId() + ":" + event.getOperation(), cursor);
                    produced.add(event);
                    return true;
                });
    }

    @Test
    void moreThan1000RequestsArePagedAndColdTenantGetsSecondTurn() {
        when(invocations.nextCandidateTenant(2L, 0L, now)).thenReturn(7L);
        when(invocations.nextCandidateTenant(2L, 7L, now)).thenReturn(8L);
        when(invocations.candidatePage(eq(2L), anyLong(), eq(now), any())).thenAnswer(call -> {
            Long tenant = call.getArgument(1); CostReconciliationTaskDO cursor = call.getArgument(3);
            long after = cursor == null ? 0 : cursor.getCursorId();
            long total = tenant == 7L ? 1001 : 1;
            return LongStream.rangeClosed(after + 1, Math.min(total, after + 100)).mapToObj(id -> {
                var row = new CostBudgetInvocationDO(); row.setId(id); row.setTenantId(tenant);
                row.setRequestId(tenant + "-" + id); row.setLeaseUntil(now.minusMinutes(1)); return row;
            }).toList();
        });
        service.discover("DAILY:date", true);
        assertEquals(12, produced.size());
        assertEquals(7L, produced.getFirst().getTenantId());
        assertEquals(8L, produced.get(1).getTenantId());
        assertEquals(1002L, produced.stream().flatMap(event -> event.getRequestIds().stream()).distinct().count());
        assertEquals(12L, run.getTaskCount());
        assertEquals(12L, produced.stream().map(CostReconciliationTaskEvent::getTaskId).distinct().count());
        verify(coordinator).close(eq(3L), anyString(), eq(true));
    }

    @Test
    void automaticCasesHaveIndependentVersionedCursorAndNoRequestMembers() {
        when(cases.nextExpiredTenant(0L, now)).thenReturn(7L);
        when(cases.expiredPage(eq(7L), eq(now), any())).thenAnswer(call -> {
            CostReconciliationTaskDO cursor = call.getArgument(2);
            long after = cursor == null ? 0 : cursor.getCursorId();
            return LongStream.rangeClosed(after + 1, Math.min(101, after + 100)).mapToObj(id -> {
                var row = new CostReconciliationCaseDO(); row.setId(id); row.setVersion(5L);
                row.setAutomaticDeadlineAt(now.minusMinutes(1)); return row;
            }).toList();
        });
        service.discover("DAILY:date", true);
        assertEquals(2, produced.size());
        assertEquals(101, produced.stream().mapToInt(event -> event.getCases().size()).sum());
        assertNull(produced.getFirst().getRequestIds());
        assertEquals(CostReconciliationTaskEvent.Operation.EXPIRE_AUTOMATIC_CASES, produced.getFirst().getOperation());
        assertTrue(produced.stream().flatMap(event -> event.getCases().stream()).allMatch(member -> member.version() == 5L));
    }

    @Test
    void discoveryFailureAndExpiredWindowAreReportedPartial() {
        when(invocations.nextCandidateTenant(2L, 0L, now)).thenThrow(new CostBudgetUnavailableException());
        assertThrows(CostBudgetUnavailableException.class, () -> service.discover("DAILY:date", true));
        verify(coordinator).close(eq(3L), anyString(), eq(false));
        reset(coordinator);
        when(coordinator.begin(eq("DAILY:date"), anyString(), eq(true))).thenReturn(run);
        run.setDiscoveryDeadlineAt(now);
        service.discover("DAILY:date", true);
        assertTrue(produced.isEmpty());
        assertEquals(0L, run.getTaskCount());
    }

    @Test
    void duplicateLegalTriggerDoesNotDiscoverAgain() {
        when(coordinator.begin(eq("DAILY:date"), anyString(), eq(true))).thenReturn(null);
        service.discover("DAILY:date", true);
        assertTrue(produced.isEmpty());
        verifyNoInteractions(invocations, cases);
    }

    @Test
    void utf8PayloadShrinksPageWithoutLosingMembersAndRotatesBothSources() {
        when(invocations.nextCandidateTenant(2L,0L,now)).thenReturn(7L);
        when(cases.nextExpiredTenant(0L,now)).thenReturn(8L);
        when(invocations.candidatePage(eq(2L),eq(7L),eq(now),any())).thenAnswer(call->{
            CostReconciliationTaskDO cursor=call.getArgument(3);
            long after=cursor==null?0:cursor.getCursorId();
            return LongStream.rangeClosed(after+1,Math.min(100,after+100)).mapToObj(id->{
                var row=new CostBudgetInvocationDO();row.setId(id);row.setTenantId(7L);row.setLeaseUntil(now);
                row.setRequestId("汉".repeat(60)+id);return row;
            }).toList();
        });
        service.discover("DAILY:date",true);
        assertEquals(2,produced.size());assertEquals(50,produced.getFirst().getRequestIds().size());
        assertEquals(100L,produced.stream().flatMap(e->e.getRequestIds().stream()).distinct().count());
        verify(coordinator).close(eq(3L),anyString(),eq(true));
    }

    @Test
    void producerFenceRefusalDoesNotAdvanceLocalCheckpointOrPretendComplete() {
        when(invocations.nextCandidateTenant(2L,0L,now)).thenReturn(7L);
        var row=new CostBudgetInvocationDO();row.setId(1L);row.setRequestId("one");row.setLeaseUntil(now);
        when(invocations.candidatePage(eq(2L),eq(7L),eq(now),any())).thenReturn(List.of(row));
        when(coordinator.append(eq(3L),anyString(),any(),any(),anyLong(),anyLong())).thenAnswer(call->{
            run.setState("CLOSING");return false;
        });
        service.discover("DAILY:date",true);
        assertEquals(0L,run.getTaskCount());assertTrue(produced.isEmpty());
        verify(coordinator).close(eq(3L),anyString(),eq(false));
    }

    @Test
    void invalidTenantCursorAndShutdownInterruptCannotGenerateUnboundedWork() {
        when(invocations.nextCandidateTenant(2L,0L,now)).thenReturn(0L);
        assertThrows(IllegalStateException.class,()->service.discover("DAILY:date",true));
        assertTrue(produced.isEmpty());verify(coordinator).close(eq(3L),anyString(),eq(false));
        Thread.currentThread().interrupt();
        try {
            service.discover("DAILY:date",true);
            assertTrue(produced.isEmpty());assertEquals(0L,run.getTaskCount());
        } finally {
            Thread.interrupted();
        }
    }
}
