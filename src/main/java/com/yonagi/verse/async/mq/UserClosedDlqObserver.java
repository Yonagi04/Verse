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

/** 将注销清理消费死信写回可靠任务，便于定位和重放。 */
@Component
@RequiredArgsConstructor
@Slf4j
@RocketMQMessageListener(topic = "%DLQ%${rocketmq.consumer.group}",
        consumerGroup = "${rocketmq.consumer.group}-user-closed-dlq-observer", selectorExpression = EventTag.USER_CLOSED)
public class UserClosedDlqObserver implements RocketMQListener<MessageExt> {
    private final DomainEventOutboxMapper outbox;

    @Override public void onMessage(MessageExt message) {
        String eventId = message.getKeys();
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("注销清理死信缺少 eventId");
        outbox.markConsumerDlq(eventId, "CONSUMER_DLQ msgId=" + message.getMsgId(), LocalDateTime.now());
        log.error("[user-cleanup-dlq] 消费重试耗尽: eventId={}, msgId={}", eventId, message.getMsgId());
    }
}
