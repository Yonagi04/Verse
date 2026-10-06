package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.cache.NoQueryAccess;
import com.yonagi.verse.common.cache.QueryCache;
import com.yonagi.verse.common.cache.QueryCacheDependencies;
import com.yonagi.verse.service.cache.HourlyCacheBehavior;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import com.yonagi.verse.service.tenant.TenantQueryAccess;

import com.yonagi.verse.common.cache.QueryCached;
import com.yonagi.verse.common.cache.QueryCacheTtl;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.NotificationErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.NotificationDO;
import com.yonagi.verse.dao.entity.NotificationRecipientDO;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.NotificationRecipientMapper;
import com.yonagi.verse.dto.req.NotificationListReqDTO;
import com.yonagi.verse.dto.resp.NotificationInfoRespDTO;
import com.yonagi.verse.dto.resp.NotificationListRespDTO;
import com.yonagi.verse.dto.resp.NotificationRecentListRespDTO;
import com.yonagi.verse.dto.resp.NotificationUnreadCountRespDTO;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.notification.NotificationCreationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

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
@QueryCacheDependencies({"t_notification"})
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, NotificationDO> implements NotificationService {

    private final TenantAccessPolicy tenantAccess;
    private final NotificationRecipientMapper notificationRecipientMapper;
    private final DomainEventPublisher domainEventPublisher;
    private final QueryCache queryCache;
    private final NotificationCreationService notificationCreationService;

    @Override
    @QueryCached(keyPrefix = NOTIFICATION_LIST_KEY, seconds = MINUTES_10, access = NoQueryAccess.class,
            tables = {"t_notification", "t_notification_recipient"}, behavior = HourlyCacheBehavior.class)
    public NotificationListRespDTO getNotificationList(Long userId, NotificationListReqDTO requestParam) {
        long startTime = System.currentTimeMillis() - Duration.ofDays(90).toMillis();
        // 筛选在数据库分页前执行，确保总条数与当前页使用相同条件。
        Page<NotificationListRespDTO.NotificationInfo> page = notificationRecipientMapper
                .selectPageByUserIdAndStartTime(new Page<>(requestParam.getPageNum(), requestParam.getPageSize()),
                        userId, startTime, requestParam);
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
        // 只缓存正文，接收人校验和已读写入仍在每次请求执行。
        NotificationDO notificationDO = queryCache.read("notification-content", RedisKeyConstant.NOTIFICATION_INFO_KEY, notificationId, NotificationDO.class,
                List.of("t_notification"), java.util.concurrent.TimeUnit.SECONDS.toMillis(QueryCacheTtl.HOURS_24), () -> baseMapper.selectOne(
                        Wrappers.lambdaQuery(NotificationDO.class).eq(NotificationDO::getNotificationId, notificationId)));
        if (notificationDO == null) throw new ClientException(NotificationErrorCodeEnum.NOTIFICATION_NOT_FOUND);
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
    @QueryCached(keyPrefix = NOTIFICATION_UNREAD_COUNT_KEY, seconds = MINUTES_10, access = NoQueryAccess.class,
            tables = {"t_notification", "t_notification_recipient"}, behavior = HourlyCacheBehavior.class)
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

    /**
     * 获取最近一天的通知列表
     * @param userId
     * @param tenantId
     * @return
     */
    @Override
    @QueryCached(keyPrefix = NOTIFICATION_RECENT_LIST_KEY, seconds = MINUTES_10, access = TenantQueryAccess.class,
            tables = {"t_tenant", "t_user_tenant", "t_notification", "t_notification_recipient"}, behavior = HourlyCacheBehavior.class)
    public NotificationRecentListRespDTO getRecentNotifications(Long userId, Long tenantId) {
        validateTenantAndMembership(tenantId, userId);
        long startTime = System.currentTimeMillis() - Duration.ofDays(1).toMillis();
        List<NotificationRecentListRespDTO.NotificationInfo> notificationInfos = notificationRecipientMapper.selectListByUserIdAndTenantIdAndStartTime(userId, tenantId, startTime);
        NotificationRecentListRespDTO notificationRecentListRespDTO = new NotificationRecentListRespDTO(new ArrayList<>());
        notificationRecentListRespDTO.setRecords(notificationInfos == null ? new ArrayList<>() : notificationInfos);
        return notificationRecentListRespDTO;
    }

    private void validateTenantAndMembership(Long tenantId, Long userId) {
        tenantAccess.requireMember(userId, tenantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createAndPush(Long tenantId, String type, String severity,
                              String title, String content, Long senderId,
                              List<Long> recipientUserIds) {
        notificationCreationService.createAndPush(notificationEvent(tenantId, type, severity,
                title, content, senderId, recipientUserIds));
    }

    @Override
    public void publishNotification(Long tenantId, String type, String severity,
                                    String title, String content, Long senderId,
                                    List<Long> recipientUserIds) {
        domainEventPublisher.publishInTx(notificationEvent(tenantId, type, severity,
                title, content, senderId, recipientUserIds));
    }

    private NotificationEvent notificationEvent(Long tenantId, String type, String severity,
                                                String title, String content, Long senderId,
                                                List<Long> recipientUserIds) {
        NotificationEvent event = new NotificationEvent();
        event.setNotificationId(SnowflakeIdUtil.nextId());
        event.setTenantId(tenantId);
        event.setType(type);
        event.setSeverity(severity);
        event.setTitle(title);
        event.setContent(content);
        event.setSenderId(senderId);
        event.setRecipientUserIds(List.copyOf(recipientUserIds));
        event.setKey(tenantId != null ? String.valueOf(tenantId) : event.getEventId());
        return event;
    }


}
