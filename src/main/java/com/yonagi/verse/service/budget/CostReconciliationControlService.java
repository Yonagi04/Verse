package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.event.CostReconciliationControlEvent;
import com.yonagi.verse.async.event.CostReconciliationControlEvent.Kind;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationTaskMapper;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 有限控制链及通知用例。控制不会查询候选请求或按清单执行业务。 */
@Service
@Slf4j
public class CostReconciliationControlService {
    private static final long CONTROL_BUDGET_NANOS = 10_000_000_000L;
    private final CostReconciliationScanCoordinator coordinator;
    private final CostReconciliationScanMapper scans;
    private final CostReconciliationTaskMapper tasks;
    private final CostReconciliationTaskService taskService;
    private final CostReconciliationBatchMapper batches;
    private final CostReconciliationNotifications notifications;
    private final CostReconciliationSignals signals;
    private final DomainEventOutboxMapper outbox;
    private final TransactionTemplate transaction;

    public CostReconciliationControlService(CostReconciliationScanCoordinator coordinator,
            CostReconciliationScanMapper scans, CostReconciliationTaskMapper tasks,
            CostReconciliationTaskService taskService, CostReconciliationBatchMapper batches,
            CostReconciliationNotifications notifications, CostReconciliationSignals signals,
            DomainEventOutboxMapper outbox, PlatformTransactionManager manager) {
        this.coordinator = coordinator;
        this.scans = scans;
        this.tasks = tasks;
        this.taskService = taskService;
        this.batches = batches;
        this.notifications = notifications;
        this.signals = signals;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(5);
    }

    public void consume(CostReconciliationControlEvent event) {
        validate(event);
        var intent = outbox.byEvent(event.getEventId());
        CostReconciliationTaskService.verifyPublishedIntent(event, intent);
        var run = scans.selectById(event.getRunId());
        if (run == null || "ACTIVATION".equals(run.getState())) {
            throw new IllegalArgumentException("missing reconciliation control run");
        }
        if (event.getKind() == Kind.CHECK_BATCH_NOTIFICATION) {
            var batch = batches.selectById(event.getBatchId());
            if (batch == null || !run.getId().equals(batch.getScanId())) {
                throw new IllegalArgumentException("notification original batch mismatch");
            }
            boolean staged = notifications.stageBatchIfReady(batch.getId());
            acknowledgeNotice(event, !staged, null, intent.getRetryUntil());
            return;
        }
        if (event.getKind() == Kind.RUN_SEALED) {
            if (!"SEALED".equals(run.getState())) {
                throw new IllegalStateException("notification run not sealed");
            }
            long after = event.getAfterBatchId();
            long started = System.nanoTime();
            var page = batches.runPage(run.getId(), after);
            int processed = 0;
            for (var batch : page) {
                if (System.nanoTime() - started >= CONTROL_BUDGET_NANOS) {
                    break;
                }
                if (!notifications.stageBatchIfReady(batch.getId())) {
                    acknowledgeNotice(event, true, null, intent.getRetryUntil());
                    return;
                }
                after = batch.getId();
                processed++;
            }
            acknowledgeNotice(event, false, processed < page.size() || page.size() == 100 ? after : null,
                    intent.getRetryUntil());
            return;
        }
        if (event.getKind() == Kind.CHECK && event.getSequence().equals(run.getControlSequence())
                && !scans.databaseTime().isBefore(run.getExecutionDeadlineAt())) {
            long started = System.nanoTime();
            for (String taskId : tasks.pendingForExpiry(run.getId())) {
                if (System.nanoTime() - started >= CONTROL_BUDGET_NANOS) {
                    break;
                }
                taskService.expire(taskId);
            }
        }
        coordinator.inspect(run.getId(), event.getKind() == Kind.CHECK ? event.getSequence() : null);
        transaction.executeWithoutResult(status -> {
            // run -> Outbox，与失败记录使用相同锁序。
            scans.lock(run.getId());
            outbox.reconcileReconciliation(event.getEventId(), scans.databaseTime());
        });
    }

    private void acknowledgeNotice(CostReconciliationControlEvent event, boolean paused, Long next,
                                   java.time.LocalDateTime retryUntil) {
        transaction.executeWithoutResult(status -> {
            var run = scans.lock(event.getRunId());
            if (paused) {
                run.setNotificationRecoveryRequired(true);
                run.setNotificationEpoch(Math.addExact(run.getNotificationEpoch(), 1));
            } else if (next != null) {
                signals.continueNotifications(event, next, retryUntil);
            } else if (event.getKind() == Kind.RUN_SEALED
                    && java.util.Objects.equals(event.getNotificationEpoch(), run.getNotificationEpoch())) {
                run.setNotificationRecoveryRequired(false);
            }
            scans.updateById(run);
            outbox.reconcileReconciliation(event.getEventId(), scans.databaseTime());
        });
    }

    public void recoverAtLegalTrigger() {
        long after = 0;
        // 与发现共用固定预算；未处理运行由下一次合法触发继续，逐轮不做业务补扫。
        long started = System.nanoTime();
        while (System.nanoTime() - started < CONTROL_BUDGET_NANOS) {
            var page = scans.recoveryPage(after);
            if (page.isEmpty()) {
                return;
            }
            for (Long id : page) {
                if (System.nanoTime() - started >= CONTROL_BUDGET_NANOS) {
                    return;
                }
                try {
                    coordinator.recover(id);
                } catch (RuntimeException failure) {
                    // 原未闭合状态/恢复标志仍在 DB；隔离单轮故障，让每日新轮次继续创建。
                    log.error("[reconciliation] 旧运行控制恢复失败 runId={}", id, failure);
                }
                after = id;
            }
        }
    }

    private void validate(CostReconciliationControlEvent event) {
        if (event == null || !Integer.valueOf(1).equals(event.getSchemaVersion()) || event.getRunId() == null
                || event.getRunId() <= 0 || event.getKind() == null || event.getSequence() == null || event.getNotificationEpoch() == null
                || event.getAfterBatchId() == null || event.getAfterBatchId() < 0
                || event.getKind() == Kind.CHECK_BATCH_NOTIFICATION && event.getBatchId() == null
                || event.getKind() != Kind.CHECK_BATCH_NOTIFICATION && event.getBatchId() != null) {
            throw new IllegalArgumentException("invalid reconciliation control");
        }
    }
}
