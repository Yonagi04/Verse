package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.DomainEventDelivery;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.common.config.CostReconciliationMqProperties;
import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import com.yonagi.verse.dao.entity.CostReconciliationTaskDO;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Objects;

/** 运行与页面发布意图的短事务；从不执行或认领业务工作。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostReconciliationScanCoordinator {
    private static final int PRODUCER_LEASE_SECONDS = 60;
    private final CostReconciliationScanMapper scans;
    private final CostReconciliationTaskMapper tasks;
    private final CostReconciliationBatchMapper batches;
    private final ReliableDomainEventPublisher events;
    private final CostReconciliationSignals signals;
    private final CostReconciliationMqProperties properties;

    /** 只由启动和每日触发调用；接管不能延长原发现窗口。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public CostReconciliationScanDO begin(String triggerKey, String token, boolean daily) {
        properties.requireBrokerPreflight();
        var marker = scans.lockActivation();
        if (marker == null || marker.getGeneration() == 0) {
            log.info("[reconciliation] 扫描跳过 trigger={} reason=CAPTURE_DISABLED", triggerKey);
            return null;
        }
        var now = scans.databaseTime();
        var existing = scans.byTrigger(triggerKey);
        var active = scans.lockActiveScan();
        if (existing != null && (active == null || !Objects.equals(existing.getId(), active.getId()))) {
            log.info("[reconciliation] 扫描跳过 trigger={} runId={} reason=TRIGGER_ALREADY_CLOSED",
                    triggerKey, existing.getId());
            return null;
        }
        if (active != null) {
            if (!now.isBefore(active.getDiscoveryDeadlineAt())
                    || daily && !triggerKey.equals(active.getTriggerKey())) {
                freeze(active, false, now);
            } else {
                if (active.getProducerLeaseUntil() != null && now.isBefore(active.getProducerLeaseUntil())) {
                    log.info("[reconciliation] 扫描跳过 trigger={} runId={} reason=PRODUCER_ACTIVE",
                            triggerKey, active.getId());
                    return null;
                }
                active.setProducerToken(token);
                active.setProducerLeaseUntil(now.plusSeconds(PRODUCER_LEASE_SECONDS));
                scans.updateById(active);
                return active;
            }
        }
        if (existing != null) {
            log.info("[reconciliation] 扫描跳过 trigger={} runId={} reason=TRIGGER_ALREADY_CLOSED",
                    triggerKey, existing.getId());
            return null;
        }
        var run = new CostReconciliationScanDO();
        run.setState("OPEN");
        run.setGeneration(marker.getGeneration());
        run.setTriggerKey(triggerKey);
        run.setCutoffAt(now);
        run.setDiscoveryDeadlineAt(now.plusSeconds(properties.getDiscoverySeconds()));
        run.setExecutionDeadlineAt(now.plusSeconds(properties.getExecutionSeconds()));
        run.setCleanupDeadlineAt(now.plusSeconds(properties.getCleanupSeconds()));
        run.setProducerToken(token);
        run.setProducerLeaseUntil(now.plusSeconds(PRODUCER_LEASE_SECONDS));
        run.setTaskCount(0L);
        run.setProductionClosed(false);
        run.setDiscoveryComplete(false);
        run.setControlSequence(0L);
        run.setControlRecoveryRequired(false);
        run.setNotificationRecoveryRequired(false);
        run.setNotificationEpoch(0L);
        scans.insert(run);
        signals.check(run, now, properties.getDiscoveryDelayLevel());
        Long runId = run.getId();
        logAfterCommit(() -> log.info("[reconciliation] 发现运行创建 runId={} trigger={}", runId, triggerKey));
        return run;
    }

    /** 页、Outbox、页序号及对应操作的游标同生共死。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public boolean append(Long runId, String token, CostReconciliationTaskEvent event,
                          LocalDateTime cursorAt, Long cursorId, long sequence) {
        var run = scans.lock(runId);
        var committed = tasks.bySequence(runId, sequence);
        if (committed != null) {
            CostReconciliationPageBarrier.verify(committed, event);
            return true;
        }
        var now = scans.databaseTime();
        if (run == null || !"OPEN".equals(run.getState()) || Boolean.TRUE.equals(run.getProductionClosed())
                || !Objects.equals(token, run.getProducerToken())
                || !now.isBefore(run.getProducerLeaseUntil()) || !now.isBefore(run.getDiscoveryDeadlineAt())) {
            return false;
        }
        if (run.getTaskCount() != sequence || !Objects.equals(run.getGeneration(), event.getGeneration())
                || !Objects.equals(runId, event.getRunId())) {
            throw new IllegalStateException("reconciliation page checkpoint conflict");
        }
        var row = new CostReconciliationTaskDO();
        row.setTaskId(event.getTaskId());
        row.setScanId(runId);
        row.setPageSequence(sequence);
        row.setTenantId(event.getTenantId());
        row.setOperation(event.getOperation().name());
        row.setPayloadHash(event.getPayloadHash());
        row.setItemCount(event.getRequestIds() == null ? event.getCases().size() : event.getRequestIds().size());
        row.setExecutionDeadlineAt(run.getExecutionDeadlineAt());
        row.setCursorAt(cursorAt);
        row.setCursorId(cursorId);
        row.setOutcome("PENDING");
        row.setCreatedAt(now);
        tasks.insert(row);
        events.enqueueOnce(event, row.getTenantId(),
                new DomainEventDelivery(properties.getTaskTopic(), 0, run.getExecutionDeadlineAt()));
        run.setTaskCount(Math.addExact(sequence, 1));
        run.setProducerLeaseUntil(now.plusSeconds(PRODUCER_LEASE_SECONDS));
        scans.updateById(run);
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public void close(Long runId, String token, boolean complete) {
        var run = scans.lock(runId);
        if (run != null && "OPEN".equals(run.getState()) && Objects.equals(token, run.getProducerToken())) {
            freeze(run, complete, scans.databaseTime());
        }
    }

    private void freeze(CostReconciliationScanDO run, boolean complete, LocalDateTime now) {
        run.setState("CLOSING");
        run.setProductionClosed(true);
        run.setDiscoveryComplete(complete);
        scans.updateById(run);
        signals.progress(run, "close-" + run.getId(), now);
        Long runId = run.getId();
        String trigger = run.getTriggerKey();
        long taskPages = run.getTaskCount();
        logAfterCommit(() -> log.info(
                "[reconciliation] 发现阶段结束 runId={} trigger={} discoveryComplete={} taskPages={}",
                runId, trigger, complete, taskPages));
    }

    /** 控制只核验当前运行与清单，不扫描请求或案件。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public void inspect(Long runId, Long checkSequence) {
        var run = scans.lock(runId);
        if (run == null || "ACTIVATION".equals(run.getState())) {
            throw new IllegalArgumentException("missing reconciliation run");
        }
        var now = scans.databaseTime();
        if ("OPEN".equals(run.getState()) && !now.isBefore(run.getDiscoveryDeadlineAt())) {
            freeze(run, false, now);
        }
        if ("SEALED".equals(run.getState())) {
            signals.sealed(run, 0);
            return;
        }
        if (Boolean.TRUE.equals(run.getProductionClosed()) && !tasks.hasPending(runId)) {
            long count = tasks.countAll(runId);
            if (count != run.getTaskCount()) {
                throw new IllegalStateException("reconciliation manifest count mismatch");
            }
            long unsuccessful = tasks.countUnsuccessful(runId);
            long batchCount = batches.selectCount(Wrappers.lambdaQuery(CostReconciliationBatchDO.class)
                    .eq(CostReconciliationBatchDO::getScanId, runId));
            run.setState("SEALED");
            run.setSealedAt(now);
            run.setNotificationDeadlineAt(now.plusSeconds(properties.getCleanupSeconds()));
            run.setCompletionReason(Boolean.TRUE.equals(run.getDiscoveryComplete()) && unsuccessful == 0
                    ? "COMPLETE" : count > 0 && unsuccessful == count ? "FAILED" : "PARTIAL");
            run.setControlRecoveryRequired(false);
            scans.updateById(run);
            signals.sealed(run, 0);
            String trigger = run.getTriggerKey();
            String reason = run.getCompletionReason();
            logAfterCommit(() -> log.info(
                    "[reconciliation] 运行封存 runId={} trigger={} reason={} taskPages={} batchCount={}",
                    runId, trigger, reason, count, batchCount));
            return;
        }
        if (checkSequence != null && Objects.equals(checkSequence, run.getControlSequence())) {
            run.setControlSequence(Math.addExact(run.getControlSequence(), 1));
            if (now.isBefore(run.getCleanupDeadlineAt())) {
                scans.updateById(run);
                boolean expiring = !now.isBefore(run.getExecutionDeadlineAt());
                signals.check(run, now, expiring ? 0 : properties.getCheckDelayLevel());
            } else {
                run.setControlRecoveryRequired(true);
                run.setLastError("CONTROL_CLEANUP_DEADLINE");
                scans.updateById(run);
                log.error("[reconciliation] 控制清理期限耗尽 runId={}", runId);
            }
        }
    }

    /** 合法触发修复持久控制；不会在此恢复候选扫描。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public void recover(Long runId) {
        var run = scans.lock(runId);
        if (run == null) {
            return;
        }
        var now = scans.databaseTime();
        if ("OPEN".equals(run.getState()) && (!now.isBefore(run.getDiscoveryDeadlineAt())
                || !now.isBefore(run.getProducerLeaseUntil()))) {
            freeze(run, false, now);
        }
        run.setControlSequence(Math.addExact(run.getControlSequence(), 1));
        if ("SEALED".equals(run.getState())) {
            signals.recoverNotifications(run, now);
        } else {
            // 恢复控制使用新的持久期限，不延长原业务执行/发现截止。
            run.setCleanupDeadlineAt(now.plusSeconds(properties.getCleanupSeconds()));
            signals.check(run, now, 0);
        }
        run.setControlRecoveryRequired(false);
        scans.updateById(run);
    }

    /** 避免将最终回滚的轮次记录为已创建或已完成；回调只写日志，不再访问数据库。 */
    private void logAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
