package com.yonagi.verse.async.mq;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = "%DLQ%${rocketmq.consumer.group}",
        consumerGroup = "${rocketmq.consumer.group}-reconciliation-dlq-observer", selectorExpression = EventTag.COST_RECONCILIATION)
public class CostReconciliationDlqObserver implements RocketMQListener<MessageExt> {
    private final DomainEventOutboxMapper outbox;

    @Override
    public void onMessage(MessageExt message) {
        String eventId = message.getKeys();
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("成本对账死信缺少事件标识");
        }
        outbox.markConsumerDlq(eventId, "CONSUMER_DLQ", LocalDateTime.now());
        log.error("[reconciliation-dlq] 消费重试耗尽 eventId={}", eventId);
    }
}
