package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.DomainEvent;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.async.outbox.DomainEventPayload;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationTaskMapper;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 消息执行用例与页终态。业务按项提交，依赖失败直接交给 Broker 重试。 */
@Service
@RequiredArgsConstructor
public class CostReconciliationTaskService {
    private static final long PAGE_BUDGET_NANOS = 30_000_000_000L;
    private final CostReconciliationTaskMapper tasks;
    private final CostReconciliationScanMapper scans;
    private final CostBudgetInvocationMapper invocations;
    private final CostReconciliationCaseMapper cases;
    private final DomainEventOutboxMapper outbox;
    private final CostReconciliationDiscovery discovery;
    private final BudgetExecutionRegistry registry;
    private final CostReconciliationSignals signals;

    public void execute(CostReconciliationTaskEvent event) {
        CostReconciliationTaskProtocol.validate(event);
        verifyPublishedIntent(event, outbox.byEvent(event.getEventId()));
        var run = scans.selectById(event.getRunId());
        if (run == null || !Objects.equals(run.getGeneration(), event.getGeneration())) {
            throw new IllegalArgumentException("reconciliation run generation mismatch");
        }
        var manifest = tasks.selectOne(Wrappers.lambdaQuery(com.yonagi.verse.dao.entity.CostReconciliationTaskDO.class)
                .eq(com.yonagi.verse.dao.entity.CostReconciliationTaskDO::getTaskId, event.getTaskId()));
        CostReconciliationPageBarrier.verify(manifest, event);
        if (!"PENDING".equals(manifest.getOutcome()) || !scans.databaseTime().isBefore(manifest.getExecutionDeadlineAt())) {
            return;
        }
        long started = System.nanoTime();
        if (event.getOperation() == CostReconciliationTaskEvent.Operation.CLASSIFY_REQUESTS) {
            var observed = invocations.selectList(Wrappers.lambdaQuery(CostBudgetInvocationDO.class)
                    .in(CostBudgetInvocationDO::getRequestId, event.getRequestIds()));
            // owner 证明在任何账务事务和页锁之外；未知不能证明免费或没有执行。
            var active = registry.confirmed(observed.stream().filter(row -> "RUNNING".equals(row.getState())).toList());
            for (String requestId : event.getRequestIds()) {
                requireBudget(started);
                var invocation = observed.stream().filter(row -> requestId.equals(row.getRequestId())).findFirst().orElse(null);
                if (!discovery.classify(event, invocation, active.contains(requestId))) {
                    return;
                }
            }
        } else {
            for (var member : event.getCases()) {
                requireBudget(started);
                if (!discovery.expireAutomatic(event, cases.selectById(member.caseId()), member.version())) {
                    return;
                }
            }
        }
    }

    /** 被适配器在逐项执行结束后经代理调用，不在 execute 内自调用事务。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public void complete(CostReconciliationTaskEvent event) {
        var page = tasks.lock(event.getTaskId());
        CostReconciliationPageBarrier.verify(page, event);
        if (!"PENDING".equals(page.getOutcome())) {
            return;
        }
        var now = scans.databaseTime();
        page.setOutcome(now.isBefore(page.getExecutionDeadlineAt()) ? "DONE" : "EXPIRED");
        page.setReason("DONE".equals(page.getOutcome()) ? "ALL_MEMBERS_CHECKED" : "EXECUTION_DEADLINE");
        page.setFinishedAt(now);
        tasks.updateById(page);
        outbox.reconcileReconciliation(page.getTaskId(), now);
        signals.progress(scans.selectById(page.getScanId()), page.getTaskId(), now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public void expire(String taskId) {
        var page = tasks.lock(taskId);
        var now = scans.databaseTime();
        if (page == null || !"PENDING".equals(page.getOutcome()) || now.isBefore(page.getExecutionDeadlineAt())) {
            return;
        }
        page.setOutcome("EXPIRED");
        page.setReason("EXECUTION_DEADLINE");
        page.setFinishedAt(now);
        tasks.updateById(page);
        signals.progress(scans.selectById(page.getScanId()), page.getTaskId(), now);
    }

    public static void verifyPublishedIntent(DomainEvent event, DomainEventOutboxDO intent) {
        if (intent == null || !Objects.equals(intent.getEventType(), event.eventType())
                || !Objects.equals(DomainEventPayload.hash(event), intent.getPayloadHash())) {
            throw new IllegalArgumentException("unrecognized reconciliation publish intent");
        }
    }

    private void requireBudget(long started) {
        if (System.nanoTime() - started >= PAGE_BUDGET_NANOS) {
            throw new CostBudgetUnavailableException();
        }
    }
}
