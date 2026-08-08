package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.common.constant.RedisKeyConstant;
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

    @Override
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
        TenantJoinRequestDO requestDO = validateJoinRequest(userId, requestId);
        LambdaUpdateWrapper<TenantJoinRequestDO> updateWrapper = Wrappers.lambdaUpdate(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getRequestId, requestId)
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
        // 邀请码使用次数+1
        inviteService.incrementUsageCount(requestDO.getInviteId());
        // 通知申请人
        try {
            notificationService.createAndPush(tenantId, "SYSTEM", "INFO",
                    "加入租户申请已批准",
                    "您加入租户「" + tenantDO.getName() + "」的申请已被管理员批准",
                    null, java.util.List.of(requestDO.getUserId()));
        } catch (Exception e) {
            log.error("Creating and pushing notification error for approve: {}", e.getMessage());
        }
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
        TenantJoinRequestDO tenantJoinRequestDO = validateJoinRequest(userId, requestId);
        int update = tenantJoinRequestMapper.update(Wrappers.lambdaUpdate(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getRequestId, requestId)
                .eq(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.PENDING.name())
                .set(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.REJECTED.name())
                .set(TenantJoinRequestDO::getReviewedAt, new Date())
                .set(TenantJoinRequestDO::getReviewedBy, userId)
                .set(TenantJoinRequestDO::getReviewComment, requestParam.getReviewComment()));
        if (update < 1) {
            log.error("Reject Join Request Error: tenant {}, requestId {}", tenantId, requestId);
            throw new ServerException(TenantErrorCodeEnum.REQUEST_STATUS_UPDATE_ERROR);
        }
        try {
            notificationService.createAndPush(tenantId, "SYSTEM", "INFO",
                    "申请被拒绝",
                    requestParam.getReviewComment() == null ? "您加入" + tenantDO.getName() + "的申请已被管理员拒绝" : "您加入" + tenantDO.getName() + "的申请已被管理员拒绝，理由：" + requestParam.getReviewComment(),
                    null, java.util.List.of(tenantJoinRequestDO.getUserId()));
        } catch (Exception e) {
            log.error("Creating and pushing notification error for reject: {}", e.getMessage());
        }
        stringRedisTemplate.delete(RedisKeyConstant.TENANT_JOIN_REQUEST_KEY + requestId);
        return Boolean.TRUE;
    }

    @Override
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
        stringRedisTemplate.opsForValue().set(RedisKeyConstant.TENANT_JOIN_REQUEST_KEY + newRequest.getRequestId(),
                JSON.toJSONString(newRequest), 30, TimeUnit.MINUTES);

        // 通知所有管理员
        List<Long> adminIdList = userTenantService.getTenantAdmins(tenantId)
                .stream().map(UserTenantDO::getUserId).toList();
        UserDO applicant = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .eq(UserDO::getDelFlag, 0));
        String applicantName = applicant != null ? applicant.getUsername() : String.valueOf(userId);
        try {
            notificationService.createAndPush(tenantId, "SYSTEM", "INFO",
                    "租户加入申请", "用户" + applicantName + "申请加入租户" + tenantName,
                    null, adminIdList);
        } catch (Exception e) {
            log.error("Creating and pushing notification error for create request: {}", e.getMessage());
        }
    }

    private TenantJoinRequestDO validateJoinRequest(Long userId, Long requestId) {
        TenantJoinRequestDO requestDO;
        String cachedJson = stringRedisTemplate.opsForValue().get(RedisKeyConstant.TENANT_JOIN_REQUEST_KEY + requestId);
        if (cachedJson != null) {
            requestDO = JSON.parseObject(cachedJson, TenantJoinRequestDO.class);
        } else {
            LambdaQueryWrapper<TenantJoinRequestDO> queryWrapper = Wrappers.lambdaQuery(TenantJoinRequestDO.class)
                    .eq(TenantJoinRequestDO::getRequestId, requestId);
            requestDO = tenantJoinRequestMapper.selectOne(queryWrapper);
        }
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
}
