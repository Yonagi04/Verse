package com.yonagi.verse.async.outbox;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.DomainEvent;
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

/** 与调用方业务修改在同一事务内写入 Outbox。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableDomainEventPublisherImpl implements ReliableDomainEventPublisher {
    private final DomainEventOutboxMapper mapper;
    private final DomainOutboxMetrics metrics;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void publish(DomainEvent event, Long tenantId) {
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
