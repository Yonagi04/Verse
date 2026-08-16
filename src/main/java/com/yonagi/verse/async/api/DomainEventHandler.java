package com.yonagi.verse.async.api;

/**
 * 领域事件处理器：消费侧业务逻辑，实现类注册为 Spring Bean，
 * 由统一 dispatcher 按 tag 分发调用，无需了解 MQ 注解。
 *
 * @param <T> 事件类型
 * @author Yonagi
 */
public interface DomainEventHandler<T extends DomainEvent> {

    /**
     * 事件类型（对应 tag）
     */
    String eventType();

    /**
     * 事件 Class，供 dispatcher 反序列化消息体
     */
    Class<T> eventClass();

    /**
     * 处理事件
     */
    void onEvent(T event);
}
