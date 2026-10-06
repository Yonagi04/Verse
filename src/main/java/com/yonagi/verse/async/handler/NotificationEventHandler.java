package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.service.notification.NotificationCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 系统通知事件消费者：复用通知创建事务，以通知业务 ID 保证重复消费幂等。 */
@Component
@RequiredArgsConstructor
public class NotificationEventHandler implements DomainEventHandler<NotificationEvent> {
    private final NotificationCreationService notificationCreationService;

    @Override
    public String eventType() {
        return EventTag.NOTIFICATION;
    }

    @Override
    public Class<NotificationEvent> eventClass() {
        return NotificationEvent.class;
    }

    @Override
    public void onEvent(NotificationEvent event) {
        notificationCreationService.createAndPushIfAbsent(event);
    }
}
