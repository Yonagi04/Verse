package com.yonagi.verse.async.event;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import lombok.Getter;
import lombok.Setter;

/** 注销清理事件只携带稳定用户标识，不携带凭证或请求线程上下文。 */
@Getter
@Setter
public class UserClosedEvent extends DomainEvent {
    /** 已注销用户的业务 ID。 */
    private Long userId;

    public static UserClosedEvent initial(Long userId) {
        UserClosedEvent event = next(userId);
        event.setEventId(initialId(userId));
        return event;
    }

    public static String initialId(Long userId) {
        return "user-closed-" + userId;
    }

    public static UserClosedEvent next(Long userId) {
        UserClosedEvent event = new UserClosedEvent();
        event.setUserId(userId);
        event.setKey("user-close:" + userId);
        return event;
    }

    @Override
    public String eventType() {
        return EventTag.USER_CLOSED;
    }
}
