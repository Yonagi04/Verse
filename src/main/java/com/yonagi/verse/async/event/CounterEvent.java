package com.yonagi.verse.async.event;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 邀请码计数事件：用户通过邀请码加入租户（直接加入或审批通过）后投递，
 * 消费者对 usage_count 原子 +1。
 *
 * @author Yonagi
 */
@Getter
@Setter
@NoArgsConstructor
public class CounterEvent extends DomainEvent {

    /**
     * 邀请码 ID（业务 ID），同时作为顺序键 key
     */
    private Long inviteId;

    @Override
    public String eventType() {
        return EventTag.COUNTER;
    }
}
