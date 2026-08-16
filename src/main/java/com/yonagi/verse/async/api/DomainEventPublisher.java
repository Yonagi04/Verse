package com.yonagi.verse.async.api;

/**
 * 领域事件发布器：业务代码唯一依赖的投递入口，不感知具体 MQ 实现。
 *
 * @author Yonagi
 */
public interface DomainEventPublisher {

    /**
     * 投递普通消息（无事务场景，立即发送）。
     */
    void publish(DomainEvent event);

    /**
     * 事务提交后投递：当前存在活动事务时注册 afterCommit 同步器，
     * 事务提交后才发送；不存在事务时立即发送。
     * 保证「主业务提交才发、回滚不发」。
     */
    void publishInTx(DomainEvent event);
}
