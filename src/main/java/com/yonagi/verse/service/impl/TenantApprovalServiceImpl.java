package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.cache.QueryCached;
import com.yonagi.verse.common.cache.QueryCatalogue.Access;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.enums.TenantActivityTargetType;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantJoinRequestStatusEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.TenantJoinRejectReqDTO;
import com.yonagi.verse.dto.resp.TenantJoinReqListRespDTO;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.TenantApprovalService;
import com.yonagi.verse.service.TenantInviteService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.helper.TenantValidationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantApprovalServiceImpl implements TenantApprovalService {

    private final TenantJoinRequestMapper tenantJoinRequestMapper;
    private final TenantValidationHelper validationHelper;
    private final UserTenantService userTenantService;
    private final StringRedisTemplate stringRedisTemplate;
    private final NotificationService notificationService;
    private final TenantInviteService inviteService;
    private final UserMapper userMapper;
    private final TenantInviteMapper tenantInviteMapper;
    private final TenantActivityRecorder activityRecorder;

    @Override
    @QueryCached(keyPrefix = TENANT_JOIN_REQUEST_LIST_KEY, seconds = MINUTES_30, access = Access.TEAM,
            tables = {"t_tenant", "t_user_tenant", "t_user", "t_tenant_join_request"})
    public TenantJoinReqListRespDTO listJoinRequests(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        if (pageSize == null) {
            pageSize = 10;
        }
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        Page<TenantJoinReqListRespDTO.TenantJoinReqInfo> pages = tenantJoinRequestMapper.selectPageByTenantId(new Page<>(pageNum, pageSize), tenantId);
        List<TenantJoinReqListRespDTO.TenantJoinReqInfo> records = pages.getRecords();
        TenantJoinReqListRespDTO resp = new TenantJoinReqListRespDTO();
        resp.setRequestList(records);
        resp.setTotal(pages.getTotal());
        resp.setTotalPages(pages.getPages());
        resp.setPage(pageNum);
        resp.setPageSize(pageSize);
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean approveJoinRequest(Long userId, Long tenantId, Long requestId) {
        TenantDO tenantDO = validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        TenantJoinRequestDO requestDO = validateJoinRequest(userId, tenantId, requestId);
        LambdaUpdateWrapper<TenantJoinRequestDO> updateWrapper = Wrappers.lambdaUpdate(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getRequestId, requestId)
                .eq(TenantJoinRequestDO::getTenantId, tenantId)
                .eq(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.PENDING.name())
                .set(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.APPROVED.name())
                .set(TenantJoinRequestDO::getReviewedAt, new Date())
                .set(TenantJoinRequestDO::getReviewedBy, userId);
        int update = tenantJoinRequestMapper.update(updateWrapper);
        if (update < 1) {
            log.error("Approve Join Request Error: tenant {}, requestId {}", tenantId, requestId);
            throw new ServerException(TenantErrorCodeEnum.REQUEST_STATUS_UPDATE_ERROR);
        }
        // 将申请人加入租户
        realJoinTenant(requestDO.getUserId(), tenantId);
        record(tenantId, TenantActivityDraft.of(TenantActivityType.MEMBER_JOINED).actor(userId)
                .target(TenantActivityTargetType.MEMBER, requestDO.getUserId(), userDisplayName(requestDO.getUserId()))
                .detail("joinSource", "APPROVAL"));
        // 邀请码使用次数+1
        inviteService.incrementUsageCount(requestDO.getInviteId());
        // 通知申请人（事务提交后异步投递）
        notificationService.publishNotification(tenantId, "SYSTEM", "INFO",
                "加入租户申请已批准",
                "您加入租户「" + tenantDO.getName() + "」的申请已被管理员批准",
                null, java.util.List.of(requestDO.getUserId()));
        // 删除缓存
        stringRedisTemplate.delete(RedisKeyConstant.TENANT_JOIN_REQUEST_KEY + requestId);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean rejectJoinRequest(Long userId, Long tenantId, Long requestId, TenantJoinRejectReqDTO requestParam) {
        TenantDO tenantDO = validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        TenantJoinRequestDO tenantJoinRequestDO = validateJoinRequest(userId, tenantId, requestId);
        int update = tenantJoinRequestMapper.update(Wrappers.lambdaUpdate(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getRequestId, requestId)
                .eq(TenantJoinRequestDO::getTenantId, tenantId)
                .eq(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.PENDING.name())
                .set(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.REJECTED.name())
                .set(TenantJoinRequestDO::getReviewedAt, new Date())
                .set(TenantJoinRequestDO::getReviewedBy, userId)
                .set(TenantJoinRequestDO::getReviewComment, requestParam.getReviewComment()));
        if (update < 1) {
            log.error("Reject Join Request Error: tenant {}, requestId {}", tenantId, requestId);
            throw new ServerException(TenantErrorCodeEnum.REQUEST_STATUS_UPDATE_ERROR);
        }
        record(tenantId, TenantActivityDraft.of(TenantActivityType.JOIN_REQUEST_REJECTED).actor(userId)
                .target(TenantActivityTargetType.MEMBER, tenantJoinRequestDO.getUserId(), userDisplayName(tenantJoinRequestDO.getUserId())));
        notificationService.publishNotification(tenantId, "SYSTEM", "INFO",
                "申请被拒绝",
                requestParam.getReviewComment() == null ? "您加入" + tenantDO.getName() + "的申请已被管理员拒绝" : "您加入" + tenantDO.getName() + "的申请已被管理员拒绝，理由：" + requestParam.getReviewComment(),
                null, java.util.List.of(tenantJoinRequestDO.getUserId()));
        stringRedisTemplate.delete(RedisKeyConstant.TENANT_JOIN_REQUEST_KEY + requestId);
        return Boolean.TRUE;
    }

    @Override
    @QueryCached(keyPrefix = TENANT_JOIN_REQUEST_UNREVIEWED_COUNT_KEY, seconds = MINUTES_30, access = Access.TEAM,
            tables = {"t_tenant", "t_user_tenant", "t_user", "t_tenant_join_request"})
    public Long getUnreviewedJoinReqCount(Long userId, Long tenantId) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        return tenantJoinRequestMapper.selectCount(Wrappers.lambdaQuery(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getTenantId, tenantId)
                .eq(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.PENDING.name()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createJoinRequest(Long userId, Long tenantId, Long inviteId, String tenantName) {
        TenantJoinRequestDO tenantJoinRequestDO = tenantJoinRequestMapper.selectOne(Wrappers.lambdaQuery(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getUserId, userId)
                .eq(TenantJoinRequestDO::getTenantId, tenantId)
                .eq(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.PENDING.name()));
        if (tenantJoinRequestDO != null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_JOIN_REQUEST_PENDING_EXISTS);
        }
        TenantJoinRequestDO newRequest = new TenantJoinRequestDO();
        newRequest.setRequestId(SnowflakeIdUtil.nextId());
        newRequest.setTenantId(tenantId);
        newRequest.setUserId(userId);
        newRequest.setInviteId(inviteId);
        newRequest.setStatus(TenantJoinRequestStatusEnum.PENDING.name());
        newRequest.setRequestedAt(new Date());
        int inserted = tenantJoinRequestMapper.insert(newRequest);
        if (inserted < 1) {
            log.error("Create tenant join request error: tenant {}, user {}", tenantId, userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_JOIN_REQUEST_CREATE_ERROR);
        }

        // 通知所有管理员
        List<Long> adminIdList = userTenantService.getTenantAdmins(tenantId)
                .stream().map(UserTenantDO::getUserId).toList();
        UserDO applicant = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .eq(UserDO::getDelFlag, 0));
        String applicantName = applicant != null ? applicant.getUsername() : String.valueOf(userId);
        notificationService.publishNotification(tenantId, "SYSTEM", "INFO",
                "租户加入申请", "用户" + applicantName + "申请加入租户" + tenantName,
                null, adminIdList);
    }

    private TenantJoinRequestDO validateJoinRequest(Long userId, Long tenantId, Long requestId) {
        // 审批状态属于写操作前置条件，实时读取数据库，不能接受缓存中的旧状态。
        // 按租户限定资源，跨租户与不存在使用同一错误，避免泄露其他租户的申请信息。
        TenantJoinRequestDO requestDO = tenantJoinRequestMapper.selectOne(Wrappers.lambdaQuery(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getRequestId, requestId)
                .eq(TenantJoinRequestDO::getTenantId, tenantId));
        if (requestDO == null) {
            throw new ClientException(TenantErrorCodeEnum.REQUEST_NOT_FOUND);
        }
        if (requestDO.getUserId().equals(userId)) {
            throw new ClientException(TenantErrorCodeEnum.REQUEST_APPROVE_SELF_ERROR);
        }
        if (!TenantJoinRequestStatusEnum.PENDING.name().equals(requestDO.getStatus())) {
            throw new ClientException(TenantErrorCodeEnum.REQUEST_HAS_BEEN_REVIEWED);
        }
        return requestDO;
    }

    /**
     * 将用户加入租户。
     * 临时方法，在 TenantMembershipService 创建后将改为调用 membershipService.joinMember()。
     */
    private void realJoinTenant(Long userId, Long tenantId) {
        Boolean result = userTenantService.createUserTenant(userId, tenantId, RoleEnum.MEMBER.name());
        if (!result) {
            log.error("Join Tenant Error: tenant {}, user {}", tenantId, userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_JOIN_ERROR);
        }
    }

    private String userDisplayName(Long userId) {
        UserDO user = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .select(UserDO::getUsername, UserDO::getNickname).eq(UserDO::getUserId, userId));
        if (user == null) return String.valueOf(userId);
        return user.getNickname() == null || user.getNickname().isBlank() ? user.getUsername() : user.getNickname();
    }

    private void record(Long tenantId, TenantActivityDraft draft) {
        activityRecorder.record(tenantId, draft);
    }
}
