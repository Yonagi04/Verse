package com.yonagi.verse.async.mq;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.api.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * RocketMQ 事件发布器适配实现。业务投递统一走 {@link DomainEventPublisher}，
 * RocketMQ 细节收在本适配层。
 *
 * <p>顺序性：使用 syncSendOrderly 按事件 key（tenantId/userId/inviteId）哈希路由到同一
 * MessageQueue，保证同 key 消息进入同一队列。</p>
 *
 * <p>回滚开关：每类副作用可独立降级为同步执行（不走 MQ，直接调用 handler），
 * 通过 {@code verse.async.*.sync-fallback} 配置开启。</p>
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RocketMQEventPublisher implements DomainEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;
    private final EventHandlerRegistry eventHandlerRegistry;

    @Value("${rocketmq.producer.topic}")
    private String topic;

    @Value("${rocketmq.producer.send-message-timeout:3000}")
    private long sendTimeout;

    @Value("${verse.async.login-log.sync-fallback:false}")
    private boolean loginLogSyncFallback;

    @Value("${verse.async.counter.sync-fallback:false}")
    private boolean counterSyncFallback;

    @Override
    public void publish(DomainEvent event) {
        dispatch(event);
    }

    @Override
    public void publishInTx(DomainEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(event);
                }
            });
        } else {
            dispatch(event);
        }
    }

    private void dispatch(DomainEvent event) {
        if (isSyncFallback(event.eventType())) {
            handleSync(event);
        } else {
            doSend(event);
        }
    }

    private boolean isSyncFallback(String eventType) {
        return switch (eventType) {
            case EventTag.LOGIN_LOG -> loginLogSyncFallback;
            case EventTag.COUNTER -> counterSyncFallback;
            default -> false;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void handleSync(DomainEvent event) {
        DomainEventHandler handler = eventHandlerRegistry.get(event.eventType());
        if (handler == null) {
            log.warn("[event-bus] 未注册的事件处理器，无法同步降级执行: tag={}", event.eventType());
            return;
        }
        try {
            handler.onEvent(event);
            log.info("[event-bus] 事件同步降级执行成功: eventType={}, eventId={}",
                    event.eventType(), event.getEventId());
        } catch (Exception e) {
            log.error("[event-bus] 事件同步降级执行失败: eventType={}, eventId={}",
                    event.eventType(), event.getEventId(), e);
        }
    }

    private void doSend(DomainEvent event) {
        try {
            String destination = topic + ":" + event.eventType();
            Message<String> message = MessageBuilder
                    .withPayload(JSON.toJSONString(event))
                    .setHeader(MessageConst.PROPERTY_KEYS, event.getKey())
                    .build();
            rocketMQTemplate.syncSendOrderly(destination, message, event.getKey(), sendTimeout);
            log.info("[event-bus] 事件投递成功: eventType={}, eventId={}, key={}",
                    event.eventType(), event.getEventId(), event.getKey());
        } catch (Exception e) {
            log.error("[event-bus] 事件投递失败: eventType={}, eventId={}",
                    event.eventType(), event.getEventId(), e);
        }
    }
}
