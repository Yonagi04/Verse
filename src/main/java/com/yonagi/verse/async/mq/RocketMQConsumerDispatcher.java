package com.yonagi.verse.async.mq;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.DomainEvent;
import com.yonagi.verse.async.api.DomainEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 统一消费者 dispatcher：监听单 topic verse-event，按 tag 反序列化并分发到对应 handler。
 * handler 抛异常时向上抛出，触发 RocketMQ 内置 RECONSUME_LATER 重试。
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = "${rocketmq.producer.topic}", consumerGroup = "${rocketmq.consumer.group}")
public class RocketMQConsumerDispatcher implements RocketMQListener<MessageExt> {

    private final EventHandlerRegistry eventHandlerRegistry;

    @Override
    public void onMessage(MessageExt messageExt) {
        String tag = messageExt.getTags();
        DomainEventHandler<?> handler = eventHandlerRegistry.get(tag);
        if (handler == null) {
            log.warn("[event-bus] 未注册的事件处理器，忽略: tag={}, msgId={}", tag, messageExt.getMsgId());
            return;
        }
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        DomainEvent event = (DomainEvent) JSON.parseObject(body, handler.eventClass());
        dispatch(handler, event);
    }

    @SuppressWarnings("unchecked")
    private <T extends DomainEvent> void dispatch(DomainEventHandler<T> handler, DomainEvent event) {
        handler.onEvent((T) event);
    }
}
