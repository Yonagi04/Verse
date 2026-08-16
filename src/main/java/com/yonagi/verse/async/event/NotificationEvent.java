package com.yonagi.verse.async.event;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 系统通知事件：由生产者构建并投递，消费者负责落库与 WebSocket 推送。
 *
 * @author Yonagi
 */
@Getter
@Setter
@NoArgsConstructor
public class NotificationEvent extends DomainEvent {

    /**
     * 通知业务 ID（雪花，生产者预生成），同时作为通知幂等键（uk_notification_id）
     */
    private Long notificationId;

    /**
     * 产生通知的租户 ID
     */
    private Long tenantId;

    /**
     * SYSTEM / ANNOUNCEMENT
     */
    private String type;

    /**
     * INFO / WARNING / CRITICAL
     */
    private String severity;

    private String title;

    private String content;

    /**
     * 发送者（系统通知为 NULL）
     */
    private Long senderId;

    private List<Long> recipientUserIds;

    @Override
    public String eventType() {
        return EventTag.NOTIFICATION;
    }
}
