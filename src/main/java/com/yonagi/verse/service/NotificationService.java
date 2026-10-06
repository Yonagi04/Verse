package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.NotificationDO;
import com.yonagi.verse.dto.req.NotificationListReqDTO;
import com.yonagi.verse.dto.resp.NotificationInfoRespDTO;
import com.yonagi.verse.dto.resp.NotificationListRespDTO;
import com.yonagi.verse.dto.resp.NotificationRecentListRespDTO;
import com.yonagi.verse.dto.resp.NotificationUnreadCountRespDTO;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 21:38
 */
public interface NotificationService extends IService<NotificationDO> {

    NotificationListRespDTO getNotificationList(Long userId, NotificationListReqDTO requestParam);

    NotificationInfoRespDTO getNotification(Long userId, Long notificationId);

    NotificationUnreadCountRespDTO getUnreadNotificationCount(Long userId);

    Integer readAllUnreadNotifications(Long userId);

    NotificationRecentListRespDTO getRecentNotifications(Long userId, Long tenantId);

    /**
     * 同步创建通知，正文和全部接收人在同一事务中落库，提交后推送。
     * 持久化失败向调用方传播并回滚；提交后的推送失败不影响已保存的通知。
     */
    void createAndPush(Long tenantId, String type, String severity,
                       String title, String content, Long senderId,
                       List<Long> recipientUserIds);

    /**
     * 异步投递系统通知：构建通知事件并在当前事务提交后投递，由消费者落库与推送。
     */
    void publishNotification(Long tenantId, String type, String severity,
                             String title, String content, Long senderId,
                             List<Long> recipientUserIds);
}
