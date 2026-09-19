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

/** 租户动态死信观察器，仅记录 eventId 与消息 ID 摘要。 */
@Slf4j @Component @RequiredArgsConstructor
@RocketMQMessageListener(topic="%DLQ%${rocketmq.consumer.group}",consumerGroup="${rocketmq.consumer.group}-tenant-activity-dlq-observer",selectorExpression=EventTag.TENANT_ACTIVITY)
public class TenantActivityDlqObserver implements RocketMQListener<MessageExt> {
    private final DomainEventOutboxMapper mapper;
    @Override public void onMessage(MessageExt message){
        String eventId=message.getKeys();
        if(eventId==null||eventId.isBlank()) throw new IllegalArgumentException("租户动态死信 message key 缺少 eventId");
        mapper.markConsumerDlq(eventId,"CONSUMER_DLQ msgId="+message.getMsgId(),LocalDateTime.now());
        log.error("[tenant-activity-dlq] 消费重试耗尽: eventId={}, msgId={}",eventId,message.getMsgId());
    }
}
