package com.yonagi.verse.async.api;

import com.yonagi.verse.common.util.SnowflakeIdUtil;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 领域事件抽象基类，承载事件 envelope（eventId / key / occurredAt）。
 * 具体事件继承此类并实现 {@link #eventType()} 返回对应 RocketMQ tag。
 * 业务代码只依赖本抽象层，不感知具体 MQ 实现。
 *
 * @author Yonagi
 */
@Getter
@Setter
public abstract class DomainEvent implements Serializable {

    /**
     * 事件唯一 ID（雪花），用于消息级去重与追踪
     */
    private String eventId;

    /**
     * 顺序键：同 key 事件路由到同一 MessageQueue（通知=tenantId / 登录=userId / 计数=inviteId）
     */
    private String key;

    /**
     * 事件产生时间（epoch millis）
     */
    private long occurredAt;

    protected DomainEvent() {
        this.eventId = String.valueOf(SnowflakeIdUtil.nextId());
        this.occurredAt = System.currentTimeMillis();
        this.key = this.eventId;
    }

    /**
     * 事件类型，对应 RocketMQ tag
     */
    public abstract String eventType();
}
