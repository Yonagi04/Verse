package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.CostReconciliationControlEvent;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationTaskMapper;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 新协议投递认领与原子失败处置；失败事务固定页/run -> Outbox 锁序。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostReconciliationDeliveryService {
    private final DomainEventOutboxMapper outbox;
    private final CostReconciliationScanMapper scans;
    private final CostReconciliationTaskMapper tasks;
    private final CostReconciliationSignals signals;

    @Transactional(rollbackFor = Exception.class, timeout = 5)
    public List<DomainEventOutboxDO> claim(String type) {
        if (!CostReconciliationTaskEvent.TYPE.equals(type) && !CostReconciliationControlEvent.TYPE.equals(type)) {
            throw new IllegalArgumentException("invalid reconciliation relay type");
        }
        var now = scans.databaseTime();
        var rows = outbox.lockReconciliation(type, now);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            String owner = UUID.randomUUID().toString();
            var expires = now.plusSeconds(30);
            if (outbox.claim(List.of(row.getId()), owner, expires, now) != 1) {
                throw new IllegalStateException("reconciliation outbox claim mismatch");
            }
            row.setClaimOwner(owner);
            row.setClaimExpiresAt(expires);
        }
        return rows;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public void failed(DomainEventOutboxDO observed, String error, boolean permanent) {
        var task = CostReconciliationTaskEvent.TYPE.equals(observed.getEventType())
                ? JSON.parseObject(observed.getPayloadJson(), CostReconciliationTaskEvent.class) : null;
        var control = task == null ? JSON.parseObject(observed.getPayloadJson(), CostReconciliationControlEvent.class) : null;
        var page = task == null ? null : tasks.lock(task.getTaskId());
        var run = control == null ? null : scans.lock(control.getRunId());
        var current = outbox.lock(observed.getId());
        var now = scans.databaseTime();
        if (current == null || !"CLAIMED".equals(current.getStatus())
                || !observed.getClaimOwner().equals(current.getClaimOwner())
                || !now.isBefore(current.getClaimExpiresAt())) {
            return;
        }
        boolean exhausted = permanent || !now.isBefore(current.getRetryUntil());
        int attempt = Math.addExact(current.getAttemptCount(), 1);
        long seconds = Math.min(300L, 1L << Math.min(20, attempt));
        if (outbox.markPublishFailure(current.getId(), current.getClaimOwner(), exhausted ? "FAILED" : "RETRY",
                exhausted ? now : now.plusSeconds(seconds), error, now) != 1) {
            throw new IllegalStateException("reconciliation delivery owner changed");
        }
        if (exhausted && page != null && "PENDING".equals(page.getOutcome())) {
            page.setOutcome("FAILED");
            page.setReason(error);
            page.setFinishedAt(now);
            tasks.updateById(page);
            signals.progress(scans.selectById(page.getScanId()), page.getTaskId(), now);
        }
        if (exhausted && run != null) {
            flagControlFailure(run, control, error);
        }
        if (exhausted) {
            log.error("[reconciliation] 可靠发布终止 eventId={} type={}", current.getEventId(), current.getEventType());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public void taskDeadLetter(CostReconciliationTaskEvent event) {
        CostReconciliationTaskProtocol.validate(event);
        var page = tasks.lock(event.getTaskId());
        CostReconciliationPageBarrier.verify(page, event);
        var intent = outbox.byEvent(event.getEventId());
        CostReconciliationTaskService.verifyPublishedIntent(event, intent);
        outbox.lock(intent.getId());
        if (!"PENDING".equals(page.getOutcome())) {
            return;
        }
        var now = scans.databaseTime();
        outbox.markConsumerDlq(event.getEventId(), "CONSUMER_DLQ", now);
        page.setOutcome("FAILED");
        page.setReason("CONSUMER_DLQ");
        page.setFinishedAt(now);
        tasks.updateById(page);
        signals.progress(scans.selectById(page.getScanId()), page.getTaskId(), now);
        log.error("[reconciliation] 页面进入死信 taskId={}", event.getTaskId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public void controlDeadLetter(CostReconciliationControlEvent event) {
        var run = scans.lock(event.getRunId());
        var intent = outbox.byEvent(event.getEventId());
        CostReconciliationTaskService.verifyPublishedIntent(event, intent);
        var current = outbox.lock(intent.getId());
        if (run == null || "ACTIVATION".equals(run.getState())) {
            throw new IllegalArgumentException("unrecognized control dead letter run");
        }
        if (current.getReconciledAt() != null) {
            return;
        }
        outbox.markConsumerDlq(event.getEventId(), "CONSUMER_DLQ", scans.databaseTime());
        flagControlFailure(run, event, "CONTROL_CONSUMER_DLQ");
        log.error("[reconciliation] 控制进入死信 runId={} eventId={}", run.getId(), event.getEventId());
    }

    private void flagControlFailure(com.yonagi.verse.dao.entity.CostReconciliationScanDO run,
                                    CostReconciliationControlEvent control, String error) {
        if (control.getKind() == CostReconciliationControlEvent.Kind.RUN_SEALED
                || control.getKind() == CostReconciliationControlEvent.Kind.CHECK_BATCH_NOTIFICATION) {
            run.setNotificationRecoveryRequired(true);
            run.setNotificationEpoch(Math.addExact(run.getNotificationEpoch(), 1));
        } else {
            run.setControlRecoveryRequired(true);
        }
        run.setLastError(error);
        scans.updateById(run);
    }
}
