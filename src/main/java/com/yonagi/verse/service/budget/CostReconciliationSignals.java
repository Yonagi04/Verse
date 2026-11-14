package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.api.DomainEventDelivery;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationControlEvent;
import com.yonagi.verse.async.event.CostReconciliationControlEvent.Kind;
import com.yonagi.verse.common.config.CostReconciliationMqProperties;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 业务事务内留下控制及原批次通知意图，不在提交后裸调用。 */
@Component
@RequiredArgsConstructor
public class CostReconciliationSignals {
    private final ReliableDomainEventPublisher events;
    private final CostReconciliationMqProperties properties;
    private final CostReconciliationBatchMapper batches;
    private final CostReconciliationScanMapper scans;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void progress(CostReconciliationScanDO run, String suffix, LocalDateTime at) {
        enqueue(run, Kind.PROGRESS, "crp-" + suffix, 0L, null, 0L, at, 0, run.getCleanupDeadlineAt());
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void check(CostReconciliationScanDO run, LocalDateTime at, int delay) {
        enqueue(run, Kind.CHECK, "crc-" + run.getId() + "-" + run.getControlSequence(),
                run.getControlSequence(), null, 0L, at, delay, run.getCleanupDeadlineAt());
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void sealed(CostReconciliationScanDO run, long after) {
        // sealed_at 持久且不可变，重复事件必须得到相同载荷与截止。
        enqueue(run, Kind.RUN_SEALED, "crs-" + run.getId() + "-" + after, 0L, null, after,
                run.getSealedAt(), 0, run.getNotificationDeadlineAt());
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void batchChanged(CostReconciliationCaseDO row) {
        var batch = batches.selectById(row.getBatchId());
        if (batch == null) {
            throw new IllegalStateException("missing original reconciliation batch");
        }
        var run = scans.selectById(batch.getScanId());
        LocalDateTime at = scans.databaseTime();
        enqueue(run, Kind.CHECK_BATCH_NOTIFICATION, "crb-" + row.getId() + "-" + row.getVersion(),
                row.getVersion(), row.getBatchId(), 0L, at, 0, at.plusSeconds(properties.getCleanupSeconds()));
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recoverNotifications(CostReconciliationScanDO run, LocalDateTime at) {
        enqueue(run, Kind.RUN_SEALED, "crn-" + run.getId() + "-" + run.getControlSequence(),
                run.getControlSequence(), null, 0L, at, 0, at.plusSeconds(properties.getCleanupSeconds()));
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void continueNotifications(CostReconciliationControlEvent original, long after,
                                      LocalDateTime retryUntil) {
        var run = scans.selectById(original.getRunId());
        run.setNotificationEpoch(original.getNotificationEpoch());
        LocalDateTime at = java.time.Instant.ofEpochMilli(original.getOccurredAt())
                .atZone(BudgetPeriodResolver.ZONE).toLocalDateTime();
        String lineage = com.yonagi.verse.async.outbox.DomainEventPayload.hash(java.util.List.of(
                original.getRunId(), original.getSequence(), original.getNotificationEpoch(), original.getOccurredAt()))
                .substring(0, 16);
        enqueue(run, Kind.RUN_SEALED, "crnp-" + run.getId() + "-" + lineage + "-" + after,
                original.getSequence(), null, after, at, 0, retryUntil);
    }

    private void enqueue(CostReconciliationScanDO run, Kind kind, String eventId, Long sequence,
                         Long batchId, Long after, LocalDateTime at, int delay, LocalDateTime retryUntil) {
        var event = new CostReconciliationControlEvent();
        event.setEventId(eventId);
        event.setKey("run-" + run.getId());
        event.setOccurredAt(at.atZone(BudgetPeriodResolver.ZONE).toInstant().toEpochMilli());
        event.setSchemaVersion(1);
        event.setRunId(run.getId());
        event.setKind(kind);
        event.setSequence(sequence);
        event.setNotificationEpoch(kind == Kind.RUN_SEALED && eventId.startsWith("crs-")
                ? 0L : run.getNotificationEpoch());
        event.setBatchId(batchId);
        event.setAfterBatchId(after);
        events.enqueueOnce(event, null, new DomainEventDelivery(properties.getControlTopic(), delay, retryUntil));
    }
}
