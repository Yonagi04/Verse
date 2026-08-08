package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.NotificationErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.NotificationDO;
import com.yonagi.verse.dao.entity.NotificationRecipientDO;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.NotificationRecipientMapper;
import com.yonagi.verse.dto.resp.NotificationInfoRespDTO;
import com.yonagi.verse.dto.resp.NotificationListRespDTO;
import com.yonagi.verse.dto.resp.NotificationUnreadCountRespDTO;
import com.yonagi.verse.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 21:38
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, NotificationDO> implements NotificationService {

    private final NotificationRecipientMapper notificationRecipientMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public NotificationListRespDTO getNotificationList(Long userId, Integer pageNum, Integer pageSize) {
        long startTime = System.currentTimeMillis() - Duration.ofDays(90).toMillis();
        Page<NotificationListRespDTO.NotificationInfo> page = notificationRecipientMapper
                .selectPageByUserIdAndStartTime(new Page<>(pageNum, pageSize), userId, startTime);
        return new NotificationListRespDTO()
                .setTotal((int) page.getTotal())
                .setRecords(page.getRecords());
    }

    @Override
    public NotificationInfoRespDTO getNotification(Long userId, Long notificationId) {
        // 查询通知接受表，如果查不到消息-个人关联就抛异常
        LambdaQueryWrapper<NotificationRecipientDO> queryWrapper = Wrappers.lambdaQuery(NotificationRecipientDO.class)
                .eq(NotificationRecipientDO::getUserId, userId)
                .eq(NotificationRecipientDO::getNotificationId, notificationId);
        NotificationRecipientDO notificationRecipientDO = notificationRecipientMapper.selectOne(queryWrapper);
        if (notificationRecipientDO == null) {
            throw new ClientException(NotificationErrorCodeEnum.NOTIFICATION_NOT_FOUND);
        }

        // 查询通知表
        NotificationDO notificationDO = baseMapper.selectOne(Wrappers.lambdaQuery(NotificationDO.class)
                .eq(NotificationDO::getNotificationId, notificationId));
        if (notificationDO == null) {
            throw new ClientException(NotificationErrorCodeEnum.NOTIFICATION_NOT_FOUND);
        }
        NotificationInfoRespDTO notificationInfoRespDTO = new NotificationInfoRespDTO();
        BeanUtil.copyProperties(notificationDO, notificationInfoRespDTO);
        notificationInfoRespDTO.setCreateTime(notificationRecipientDO.getCreateTime());

        // 更新通知接受表的已读状态
        LambdaUpdateWrapper<NotificationRecipientDO> updateWrapper = Wrappers.lambdaUpdate(NotificationRecipientDO.class)
                .eq(NotificationRecipientDO::getNotificationId, notificationId)
                .eq(NotificationRecipientDO::getUserId, userId)
                .set(NotificationRecipientDO::getIsRead, 1)
                .set(NotificationRecipientDO::getReadTime, new Date());
        int update = notificationRecipientMapper.update(updateWrapper);
        if (update < 0) {
            log.error("[notification] 标记通知为已读失败: userId={}, notificationId={}", userId, notificationId);
            throw new ClientException(NotificationErrorCodeEnum.NOTIFICATION_READ_FAILED);
        }

        return notificationInfoRespDTO;
    }

    @Override
    public NotificationUnreadCountRespDTO getUnreadNotificationCount(Long userId) {
        long startTime = System.currentTimeMillis() - Duration.ofDays(90).toMillis();
        Long count = notificationRecipientMapper.selectCount(Wrappers.lambdaQuery(NotificationRecipientDO.class)
                .eq(NotificationRecipientDO::getUserId, userId)
                .eq(NotificationRecipientDO::getIsRead, 0)
                .ge(NotificationRecipientDO::getCreateTime, new Date(startTime)));

        return new NotificationUnreadCountRespDTO(count);
    }

    @Override
    public Integer readAllUnreadNotifications(Long userId) {
        long startTime = System.currentTimeMillis() - Duration.ofDays(90).toMillis();
        LambdaUpdateWrapper<NotificationRecipientDO> updateWrapper = Wrappers.lambdaUpdate(NotificationRecipientDO.class)
                .eq(NotificationRecipientDO::getUserId, userId)
                .eq(NotificationRecipientDO::getIsRead, 0)
                .ge(NotificationRecipientDO::getCreateTime, new Date(startTime))
                .set(NotificationRecipientDO::getIsRead, 1)
                .set(NotificationRecipientDO::getReadTime, new Date());
        int updatedRows = notificationRecipientMapper.update(updateWrapper);
        if (updatedRows < 0) {
            log.error("[notification] 批量标记通知为已读失败: userId={}", userId);
            throw new ClientException(NotificationErrorCodeEnum.NOTIFICATION_READ_FAILED);
        }
        return updatedRows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createAndPush(Long tenantId, String type, String severity,
                              String title, String content, Long senderId,
                              List<Long> recipientUserIds) {
        try {
            Long notificationId = SnowflakeIdUtil.nextId();
            NotificationDO notification = new NotificationDO();
            notification.setNotificationId(notificationId);
            notification.setTenantId(tenantId);
            notification.setType(type);
            notification.setSeverity(severity);
            notification.setTitle(title);
            notification.setContent(content);
            notification.setSenderId(senderId);
            baseMapper.insert(notification);

            List<NotificationRecipientDO> recipients = recipientUserIds.stream().map(uid -> {
                NotificationRecipientDO r = new NotificationRecipientDO();
                r.setUserId(uid);
                r.setNotificationId(notificationId);
                r.setIsRead(0);
                return r;
            }).toList();
            for (NotificationRecipientDO recipient : recipients) {
                notificationRecipientMapper.insert(recipient);
            }

            // WebSocket 实时推送
            try {
                NotificationInfoRespDTO dto = new NotificationInfoRespDTO();
                dto.setNotificationId(notificationId);
                dto.setType(type);
                dto.setSeverity(severity);
                dto.setTitle(title);
                dto.setContent(content);
                dto.setSenderId(senderId);
                dto.setCreateTime(notification.getCreateTime());

                for (Long userId : recipientUserIds) {
                    messagingTemplate.convertAndSendToUser(
                            userId.toString(),
                            "/queue/notifications",
                            dto
                    );
                    Long unreadCount = notificationRecipientMapper.selectCount(
                            Wrappers.lambdaQuery(NotificationRecipientDO.class)
                                    .eq(NotificationRecipientDO::getUserId, userId)
                                    .eq(NotificationRecipientDO::getIsRead, 0)
                    );
                    messagingTemplate.convertAndSendToUser(
                            userId.toString(),
                            "/queue/notifications/unread-count",
                            new NotificationUnreadCountRespDTO(unreadCount)
                    );
                }
            } catch (Exception e) {
                log.error("[notification] WebSocket 推送失败: notificationId={}", notificationId, e);
                // 推送失败不影响主业务
            }
        } catch (Exception e) {
            log.error("[notification] 通知创建失败: tenantId={}, type={}, severity={}", tenantId, type, severity, e);
            // 不抛出，不阻断主业务流程
        }
    }


}
