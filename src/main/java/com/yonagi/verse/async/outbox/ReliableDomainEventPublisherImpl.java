package com.yonagi.verse.async.outbox;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.DomainEvent;
import com.yonagi.verse.async.api.DomainEventDelivery;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.async.event.CostReconciliationControlEvent;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.common.enums.DomainEventOutboxStatus;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;

/** 与调用方业务修改在同一事务内写入 Outbox。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableDomainEventPublisherImpl implements ReliableDomainEventPublisher {
    private final DomainEventOutboxMapper mapper;
    private final DomainOutboxMetrics metrics;
    @Value("${verse.llm.cost-reconciliation.task-topic:verse-cost-reconciliation-task}")
    private String taskTopic;
    @Value("${verse.llm.cost-reconciliation.control-topic:verse-cost-reconciliation-control}")
    private String controlTopic;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void enqueueOnce(DomainEvent event, Long tenantId, DomainEventDelivery delivery) {
        boolean task = CostReconciliationTaskEvent.TYPE.equals(event.eventType());
        boolean control = CostReconciliationControlEvent.TYPE.equals(event.eventType());
        if ((!task && !control) || task && tenantId == null || control && tenantId != null
                || delivery == null || delivery.retryUntil() == null
                || !Objects.equals(delivery.destination(), task ? taskTopic : controlTopic)
                || delivery.delayLevel() == null || delivery.delayLevel() < 0 || delivery.delayLevel() > 18
                || event.getEventId() == null || event.getEventId().length() > 64) {
            throw new IllegalArgumentException("invalid reconciliation delivery");
        }
        String payload = JSON.toJSONString(event);
        String hash = DomainEventPayload.hash(payload);
        DomainEventOutboxDO row = new DomainEventOutboxDO();
        row.setEventId(event.getEventId());
        row.setTenantId(tenantId);
        row.setEventType(event.eventType());
        row.setMessageKey(event.getKey());
        row.setPayloadJson(payload);
        row.setPayloadHash(hash);
        row.setDestination(delivery.destination());
        row.setDelayLevel(delivery.delayLevel());
        row.setRetryUntil(delivery.retryUntil());
        row.setStatus(DomainEventOutboxStatus.PENDING.name());
        row.setAttemptCount(0);
        LocalDateTime now = LocalDateTime.now();
        row.setNextRetryAt(now);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        mapper.insertOnce(row);
        DomainEventOutboxDO stored = mapper.byEvent(event.getEventId());
        if (stored == null || !Objects.equals(hash, stored.getPayloadHash())
                || !Objects.equals(tenantId, stored.getTenantId())
                || !Objects.equals(delivery.destination(), stored.getDestination())
                || !Objects.equals(delivery.delayLevel(), stored.getDelayLevel())
                || !Objects.equals(delivery.retryUntil(), stored.getRetryUntil())) {
            throw new IllegalStateException("reconciliation event identity conflict");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void publish(DomainEvent event, Long tenantId) {
        if (tenantId == null || CostReconciliationTaskEvent.TYPE.equals(event.eventType())
                || CostReconciliationControlEvent.TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException("tenant event requires default delivery");
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            DomainEventOutboxDO row = new DomainEventOutboxDO();
            row.setEventId(event.getEventId()); row.setTenantId(tenantId); row.setEventType(event.eventType());
            row.setMessageKey(event.getKey()); row.setPayloadJson(JSON.toJSONString(event));
            row.setStatus(DomainEventOutboxStatus.PENDING.name()); row.setAttemptCount(0);
            row.setNextRetryAt(now); row.setCreateTime(now); row.setUpdateTime(now);
            mapper.insert(row); metrics.staged();
        } catch (RuntimeException e) {
            metrics.stagingFailed();
            log.error("[domain-outbox] 暂存失败: eventId={}, tenantId={}", event.getEventId(), tenantId, e);
            throw e;
        }
    }
}
