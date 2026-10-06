package com.yonagi.verse.service.notification;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.NotificationErrorCodeEnum;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** 通知正文与接收人原子落库，提交后尽力推送；同步入口与事件消费者共用此边界。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCreationService {
    private final NotificationMapper notificationMapper;
    private final NotificationRecipientMapper recipientMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional(rollbackFor = Exception.class)
    public void createAndPush(NotificationEvent event) {
        List<Long> recipientUserIds = List.copyOf(event.getRecipientUserIds());
        NotificationDO notification = insertNotification(event);
        persistRecipientsAndSchedulePush(notification, recipientUserIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public void createAndPushIfAbsent(NotificationEvent event) {
        List<Long> recipientUserIds = List.copyOf(event.getRecipientUserIds());
        NotificationDO notification;
        try {
            notification = insertNotification(event);
        } catch (DuplicateKeyException failure) {
            // 只处理正文插入的已确认业务 ID 冲突；接收人冲突必须传播并回滚。
            // 不先查再插，避免并发消费时一致性读快照看不到刚提交的竞争者。
            Long existing = notificationMapper.selectCount(Wrappers.lambdaQuery(NotificationDO.class)
                    .eq(NotificationDO::getNotificationId, event.getNotificationId()));
            if (existing == null || existing == 0) throw failure;
            log.info("[notification] 通知已存在，跳过重复消费: notificationId={}", event.getNotificationId());
            return;
        }
        persistRecipientsAndSchedulePush(notification, recipientUserIds);
    }

    private NotificationDO insertNotification(NotificationEvent event) {
        NotificationDO notification = new NotificationDO();
        notification.setNotificationId(event.getNotificationId());
        notification.setTenantId(event.getTenantId());
        notification.setType(event.getType());
        notification.setSeverity(event.getSeverity());
        notification.setTitle(event.getTitle());
        notification.setContent(event.getContent());
        notification.setSenderId(event.getSenderId());
        if (notificationMapper.insert(notification) != 1) {
            throw new ServerException(NotificationErrorCodeEnum.NOTIFICATION_CREATE_FAILED);
        }
        return notification;
    }

    private void persistRecipientsAndSchedulePush(NotificationDO notification, List<Long> recipientUserIds) {
        for (Long userId : recipientUserIds) {
            NotificationRecipientDO recipient = new NotificationRecipientDO();
            recipient.setUserId(userId);
            recipient.setNotificationId(notification.getNotificationId());
            recipient.setIsRead(0);
            if (recipientMapper.insert(recipient) != 1) {
                throw new ServerException(NotificationErrorCodeEnum.NOTIFICATION_CREATE_FAILED);
            }
        }

        NotificationInfoRespDTO dto = new NotificationInfoRespDTO();
        dto.setNotificationId(notification.getNotificationId());
        dto.setType(notification.getType());
        dto.setSeverity(notification.getSeverity());
        dto.setTitle(notification.getTitle());
        dto.setContent(notification.getContent());
        dto.setSenderId(notification.getSenderId());
        dto.setCreateTime(notification.getCreateTime());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                push(dto, recipientUserIds);
            }
        });
    }

    private void push(NotificationInfoRespDTO notification, List<Long> recipientUserIds) {
        for (Long userId : recipientUserIds) {
            try {
                messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/notifications", notification);
                // 提交后的实时计数不缓存，避免推送本次写入前的未读数。
                Long unreadCount = recipientMapper.selectCount(Wrappers.lambdaQuery(NotificationRecipientDO.class)
                        .eq(NotificationRecipientDO::getUserId, userId)
                        .eq(NotificationRecipientDO::getIsRead, 0));
                messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/notifications/unread-count",
                        new NotificationUnreadCountRespDTO(unreadCount));
            } catch (Exception failure) {
                // 数据已经提交，推送失败不能让调用方误判落库失败，也不能阻止其他接收人推送。
                log.error("[notification] WebSocket 推送失败: notificationId={}, userId={}",
                        notification.getNotificationId(), userId, failure);
            }
        }
    }
}
