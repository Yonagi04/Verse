package com.yonagi.verse.async.mq;

import com.yonagi.verse.async.api.DomainEventHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件处理器注册表：将 Spring 容器中所有 {@link DomainEventHandler} 按 eventType 索引。
 *
 * @author Yonagi
 */
@Component
public class EventHandlerRegistry {

    private final Map<String, DomainEventHandler<?>> handlers = new ConcurrentHashMap<>();

    public EventHandlerRegistry(List<DomainEventHandler<?>> handlerList) {
        handlerList.forEach(handler -> handlers.put(handler.eventType(), handler));
    }

    public DomainEventHandler<?> get(String eventType) {
        return handlers.get(eventType);
    }
}
