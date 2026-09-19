package com.yonagi.verse.async.api;

/** 要求调用方已有数据库事务的可靠领域事件发布入口。 */
public interface ReliableDomainEventPublisher {
    /** 在当前业务事务内暂存事件。 */
    void publish(DomainEvent event, Long tenantId);
}
