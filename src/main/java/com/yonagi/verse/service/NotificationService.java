package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.NotificationDO;
import com.yonagi.verse.dto.resp.NotificationInfoRespDTO;
import com.yonagi.verse.dto.resp.NotificationListRespDTO;
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

    NotificationListRespDTO getNotificationList(Long userId, Integer pageNum, Integer pageSize);

    NotificationInfoRespDTO getNotification(Long userId, Long notificationId);

    NotificationUnreadCountRespDTO getUnreadNotificationCount(Long userId);

    Integer readAllUnreadNotifications(Long userId);

    /**
     * 创建通知并推送给指定用户（同步）。
     * 仅站内公告等主业务场景使用；调用者需要自己 catch 异常——通知发送失败不应阻塞主业务流程。
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
