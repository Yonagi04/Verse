package com.yonagi.verse.async.mq;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/** 计费用量消费死信观察器：保留原 Outbox 载荷并转为可人工重放状态。 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "%DLQ%${rocketmq.consumer.group}",
        consumerGroup = "${rocketmq.consumer.group}-usage-dlq-observer",
        selectorExpression = EventTag.TOKEN_USAGE)
public class TokenUsageDlqObserver implements RocketMQListener<MessageExt> {
    private final TokenUsageOutboxMapper outboxMapper;

    @Override
    public void onMessage(MessageExt message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        TokenUsageEvent event = JSON.parseObject(body, TokenUsageEvent.class);
        if (event == null || event.getEventId() == null) {
            throw new IllegalArgumentException("计费用量死信缺少 eventId");
        }
        outboxMapper.markConsumerDlq(event.getEventId(), "CONSUMER_DLQ msgId=" + message.getMsgId(),
                LocalDateTime.now());
        log.error("[token-usage-dlq] 消费重试耗尽，等待人工重放: eventId={}, msgId={}",
                event.getEventId(), message.getMsgId());
    }
}
