package com.yonagi.verse.async.handler;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.dao.entity.NotificationDO;
import com.yonagi.verse.dao.entity.NotificationRecipientDO;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.NotificationRecipientMapper;
import com.yonagi.verse.dto.resp.NotificationInfoRespDTO;
import com.yonagi.verse.dto.resp.NotificationUnreadCountRespDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 系统通知事件消费者：落 t_notification + t_notification_recipient 并 WebSocket 推送。
 * 以 notification_id 作为幂等键（uk_notification_id 唯一索引兜底）。
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventHandler implements DomainEventHandler<NotificationEvent> {

    private final NotificationMapper notificationMapper;
    private final NotificationRecipientMapper notificationRecipientMapper;
    private final SimpMessagingTemplate messagingTemplate;

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
        // 幂等：已处理则跳过
        Long existing = notificationMapper.selectCount(Wrappers.lambdaQuery(NotificationDO.class)
                .eq(NotificationDO::getNotificationId, event.getNotificationId()));
        if (existing != null && existing > 0) {
            log.info("[notification] 通知已存在，跳过重复消费: notificationId={}", event.getNotificationId());
            return;
        }

        NotificationDO notification = new NotificationDO();
        notification.setNotificationId(event.getNotificationId());
        notification.setTenantId(event.getTenantId());
        notification.setType(event.getType());
        notification.setSeverity(event.getSeverity());
        notification.setTitle(event.getTitle());
        notification.setContent(event.getContent());
        notification.setSenderId(event.getSenderId());
        try {
            notificationMapper.insert(notification);
        } catch (DuplicateKeyException e) {
            log.info("[notification] 通知唯一键冲突，跳过: notificationId={}", event.getNotificationId());
            return;
        }

        List<NotificationRecipientDO> recipients = event.getRecipientUserIds().stream().map(uid -> {
            NotificationRecipientDO r = new NotificationRecipientDO();
            r.setUserId(uid);
            r.setNotificationId(event.getNotificationId());
            r.setIsRead(0);
            return r;
        }).toList();
        for (NotificationRecipientDO recipient : recipients) {
            notificationRecipientMapper.insert(recipient);
        }

        push(event, notification.getCreateTime());
    }

    private void push(NotificationEvent event, Date createTime) {
        try {
            NotificationInfoRespDTO dto = new NotificationInfoRespDTO();
            dto.setNotificationId(event.getNotificationId());
            dto.setType(event.getType());
            dto.setSeverity(event.getSeverity());
            dto.setTitle(event.getTitle());
            dto.setContent(event.getContent());
            dto.setSenderId(event.getSenderId());
            dto.setCreateTime(createTime);

            for (Long userId : event.getRecipientUserIds()) {
                messagingTemplate.convertAndSendToUser(
                        userId.toString(),
                        "/queue/notifications",
                        dto);
                Long unreadCount = notificationRecipientMapper.selectCount(
                        Wrappers.lambdaQuery(NotificationRecipientDO.class)
                                .eq(NotificationRecipientDO::getUserId, userId)
                                .eq(NotificationRecipientDO::getIsRead, 0));
                messagingTemplate.convertAndSendToUser(
                        userId.toString(),
                        "/queue/notifications/unread-count",
                        new NotificationUnreadCountRespDTO(unreadCount));
            }
        } catch (Exception e) {
            log.error("[notification] WebSocket 推送失败: notificationId={}", event.getNotificationId(), e);
        }
    }
}
