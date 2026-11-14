package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.async.outbox.DomainEventPayload;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证页用例的终态/拒绝决策；数据库原子性、锁与 Broker 确认由受控环境处理。 */
class CostReconciliationTaskServiceTest {
    private final CostReconciliationTaskMapper tasks = mock(CostReconciliationTaskMapper.class);
    private final CostReconciliationScanMapper scans = mock(CostReconciliationScanMapper.class);
    private final CostBudgetInvocationMapper invocations = mock(CostBudgetInvocationMapper.class);
    private final CostReconciliationCaseMapper cases = mock(CostReconciliationCaseMapper.class);
    private final DomainEventOutboxMapper outbox = mock(DomainEventOutboxMapper.class);
    private final CostReconciliationDiscovery discovery = mock(CostReconciliationDiscovery.class);
    private final BudgetExecutionRegistry registry = mock(BudgetExecutionRegistry.class);
    private final CostReconciliationSignals signals = mock(CostReconciliationSignals.class);
    private final CostReconciliationTaskService service = new CostReconciliationTaskService(tasks,scans,invocations,
            cases,outbox,discovery,registry,signals);
    private final LocalDateTime now = LocalDateTime.of(2026,11,14,8,0);
    private CostReconciliationTaskEvent event;
    private CostReconciliationTaskDO page;
    private CostReconciliationScanDO run;

    @BeforeEach
    void setup() {
        event = new CostReconciliationTaskEvent();
        event.setSchemaVersion(1); event.setTaskId("crt-3-0"); event.setEventId("crt-3-0");
        event.setRunId(3L);event.setGeneration(2L);event.setTenantId(7L);
        event.setOperation(CostReconciliationTaskEvent.Operation.CLASSIFY_REQUESTS);
        event.setRequestIds(List.of("one","two"));event.setPayloadHash(CostReconciliationTaskProtocol.hash(event));
        page = new CostReconciliationTaskDO(); page.setScanId(3L);page.setTaskId(event.getTaskId());
        page.setTenantId(7L);page.setOperation(event.getOperation().name());page.setPayloadHash(event.getPayloadHash());
        page.setOutcome("PENDING");page.setExecutionDeadlineAt(now.plusDays(1));
        when(tasks.selectOne(any())).thenReturn(page);when(tasks.lock(event.getTaskId())).thenReturn(page);
        run = new CostReconciliationScanDO();run.setId(3L);run.setGeneration(2L);
        when(scans.selectById(3L)).thenReturn(run);when(scans.databaseTime()).thenReturn(now);
        register();
    }

    @Test
    void fullPageProducesIrreversibleDoneAndDuplicateDoesNotRestartBusiness() {
        service.complete(event);
        assertEquals("DONE",page.getOutcome());assertEquals(now,page.getFinishedAt());
        assertEquals("ALL_MEMBERS_CHECKED",page.getReason());
        service.complete(event);service.execute(event);
        assertEquals("DONE",page.getOutcome());
        verifyNoInteractions(discovery,registry);
        verify(signals).progress(run,event.getTaskId(),now);
    }

    @Test
    void hardDeadlineExpiresPageAndNeverMarksUnknownBusinessSettled() {
        page.setExecutionDeadlineAt(now);
        service.execute(event);service.complete(event);
        assertEquals("EXPIRED",page.getOutcome());assertEquals("EXECUTION_DEADLINE",page.getReason());
        verifyNoInteractions(invocations,cases,discovery,registry);
    }

    @Test
    void currentRequestMembersArePassedWithOwnerProofAndDependencyFailureRemainsRetryable() {
        var one = new CostBudgetInvocationDO();one.setRequestId("one");one.setState("RUNNING");
        var two = new CostBudgetInvocationDO();two.setRequestId("two");two.setState("FINALIZING");
        when(invocations.selectList(any())).thenReturn(List.of(one,two));
        when(registry.confirmed(List.of(one))).thenReturn(Set.of("one"));
        when(discovery.classify(event,one,true)).thenReturn(true);
        when(discovery.classify(event,two,false)).thenThrow(new CostBudgetUnavailableException());
        assertThrows(CostBudgetUnavailableException.class,()->service.execute(event));
        assertEquals("PENDING",page.getOutcome());assertNull(page.getFinishedAt());
        verify(discovery).classify(event,one,true);verify(discovery).classify(event,two,false);
        verifyNoInteractions(signals);
    }

    @Test
    void caseOperationUsesExpectedVersionAndDoesNotQueryRequestCandidatesOrOwners() {
        event.setOperation(CostReconciliationTaskEvent.Operation.EXPIRE_AUTOMATIC_CASES);
        event.setRequestIds(null);event.setCases(List.of(new CostReconciliationTaskEvent.CaseVersion(11L,5L)));
        event.setPayloadHash(CostReconciliationTaskProtocol.hash(event));
        page.setOperation(event.getOperation().name());page.setPayloadHash(event.getPayloadHash());register();
        var row = new CostReconciliationCaseDO();row.setId(11L);row.setBatchId(99L);row.setVersion(5L);
        when(cases.selectById(11L)).thenReturn(row);
        when(discovery.expireAutomatic(event,row,5L)).thenReturn(true);
        service.execute(event);
        assertEquals(99L,row.getBatchId());assertEquals("PENDING",page.getOutcome());
        verify(discovery).expireAutomatic(event,row,5L);
        verifyNoInteractions(invocations,registry);
    }

    @Test
    void generationAndPersistedPayloadMismatchRejectsForgedMembers() {
        run.setGeneration(1L);
        assertThrows(IllegalArgumentException.class,()->service.execute(event));
        run.setGeneration(2L);event.setRequestIds(List.of("foreign"));
        event.setPayloadHash(CostReconciliationTaskProtocol.hash(event));
        assertThrows(IllegalArgumentException.class,()->service.execute(event));
        assertEquals("PENDING",page.getOutcome());verifyNoInteractions(discovery);
    }

    @Test
    void expirationCannotDowngradeDoneAndFuturePageRemainsPending() {
        service.expire(event.getTaskId());assertEquals("PENDING",page.getOutcome());
        page.setExecutionDeadlineAt(now);service.expire(event.getTaskId());
        assertEquals("EXPIRED",page.getOutcome());assertEquals(now,page.getFinishedAt());
        page.setOutcome("DONE");service.expire(event.getTaskId());
        assertEquals("DONE",page.getOutcome());
    }

    @Test
    void concurrentTerminalStopsRemainingMembersAndCannotBeOverwrittenByCompletion() {
        var one=new CostBudgetInvocationDO();one.setRequestId("one");one.setState("RUNNING");
        when(invocations.selectList(any())).thenReturn(List.of(one));
        when(registry.confirmed(any())).thenReturn(Set.of());
        when(discovery.classify(event,one,false)).thenAnswer(call->{page.setOutcome("FAILED");return false;});
        service.execute(event);service.complete(event);
        assertEquals("FAILED",page.getOutcome());verify(discovery,never()).classify(eq(event),isNull(),anyBoolean());
        verifyNoInteractions(signals);
    }

    private void register() {
        var intent = new DomainEventOutboxDO();intent.setEventType(event.eventType());
        intent.setPayloadHash(DomainEventPayload.hash(event));
        when(outbox.byEvent(event.getEventId())).thenReturn(intent);
    }
}
