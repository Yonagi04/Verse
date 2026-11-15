package com.yonagi.verse.async.outbox;

import com.yonagi.verse.async.event.CostReconciliationControlEvent;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.common.config.CostReconciliationMqProperties;
import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import com.yonagi.verse.service.budget.CostReconciliationDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每次只认领一条、单次发送，控制保留独立预算；旧域事件不与任务争发件批次。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostReconciliationOutboxRelay {
    private static final long SEND_TIMEOUT_MS = 3000;
    private final CostReconciliationDeliveryService delivery;
    private final DomainEventOutboxMapper outbox;
    private final CostReconciliationScanMapper scans;
    private final RocketMQTemplate mq;
    private final CostReconciliationMqProperties properties;
    private final DomainOutboxProperties outboxProperties;

    @Scheduled(fixedDelay = 1000, scheduler = "costReconciliationRelayScheduler")
    public void relay() {
        if (!outboxProperties.isRelayEnabled()) {
            return;
        }
        // 最多 8 个发送、每次 3 秒；控制与 WORK 各四个，单页不延长其他消息的认领。
        for (int index = 0; index < 4; index++) {
            sendNext(CostReconciliationControlEvent.TYPE);
            sendNext(CostReconciliationTaskEvent.TYPE);
        }
    }

    private void sendNext(String type) {
        try {
            for (var row : delivery.claim(type)) {
                send(row);
            }
        } catch (RuntimeException failure) {
            log.error("[reconciliation-relay] 发件批次失败 type={}", type, failure);
        }
    }

    private void send(DomainEventOutboxDO row) {
        var now = scans.databaseTime();
        if (!outbox.owns(row.getId(), row.getClaimOwner(), now)) {
            return;
        }
        boolean task = CostReconciliationTaskEvent.TYPE.equals(row.getEventType());
        String destination = task ? properties.getTaskTopic() : properties.getControlTopic();
        if (!destination.equals(row.getDestination()) || row.getRetryUntil() == null
                || row.getDelayLevel() == null || row.getDelayLevel() < 0 || row.getDelayLevel() > 18) {
            delivery.failed(row, "INVALID_DELIVERY", true);
            return;
        }
        if (!now.isBefore(row.getRetryUntil())) {
            delivery.failed(row, "PUBLISH_DEADLINE", true);
            return;
        }
        try {
            var message = MessageBuilder.withPayload(row.getPayloadJson())
                    .setHeader(MessageConst.PROPERTY_KEYS, row.getEventId()).build();
            var sent = mq.syncSend(destination + ":" + row.getEventType(), message, SEND_TIMEOUT_MS, row.getDelayLevel());
            if (sent == null || sent.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker did not acknowledge reconciliation message");
            }
            if (outbox.markPublished(row.getId(), row.getClaimOwner(), scans.databaseTime()) != 1) {
                throw new IllegalStateException("reconciliation publish result unknown");
            }
        } catch (RuntimeException failure) {
            // 不把客户端错误正文持久化，避免端点或鉴权资料进入业务审计。
            delivery.failed(row, failure.getClass().getSimpleName(), false);
            log.warn("[reconciliation-relay] 发送待重试 eventId={}", row.getEventId(), failure);
        }
    }
}
