package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.TenantJoinReqDTO;
import com.yonagi.verse.dto.req.TenantMemberRoleUpdateReqDTO;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.TenantApprovalService;
import com.yonagi.verse.service.TenantInviteService;
import com.yonagi.verse.service.TenantMembershipService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.helper.TenantValidationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantMembershipServiceImpl implements TenantMembershipService {

    private static final Map<String, String> ROLE_DISPLAY_MAP = Map.of(
            "SUPER_ADMIN", "超级管理员",
            "ADMIN", "管理员",
            "MEMBER", "成员"
    );

    private static final String LEAVE_TENANT_WARNING_DESCRIPTION = "离开租户后将导致该租户下的所有数据无法访问，请谨慎操作。";
    private static final List<String> LEAVE_TENANT_WARNING_TIPS = List.of(
            "离开租户后将无法访问该租户下的所有数据",
            "离开租户后将无法访问该租户下的所有 LLM 服务",
            "离开租户后将无法访问该租户下的所有历史审计记录",
            "离开租户后将无法访问该租户下的所有 API Key",
            "您可以重新加入该租户，但需要管理员重新邀请您"
    );

    private final UserTenantService userTenantService;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final TenantValidationHelper validationHelper;
    private final NotificationService notificationService;
    private final TenantInviteService inviteService;
    private final TenantApprovalService approvalService;

    @Override
    public TenantLeavePrepareRespDTO prepareLeaveTenant(Long userId, Long tenantId) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.PERSONAL_TENANT_CAN_NOT_LEAVE);
        Boolean isUserJoinTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isUserJoinTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        return new TenantLeavePrepareRespDTO(LEAVE_TENANT_WARNING_DESCRIPTION, LEAVE_TENANT_WARNING_TIPS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantLeaveRespDTO leaveTenant(Long userId, Long tenantId) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.PERSONAL_TENANT_CAN_NOT_LEAVE);
        Boolean isUserJoinTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isUserJoinTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        String role = userTenantService.getRoleByUserIdAndTenantId(userId, tenantId);
        if (RoleEnum.SUPER_ADMIN.name().equals(role)) {
            throw new ClientException(TenantErrorCodeEnum.SUPER_ADMIN_LEAVE_TENANT_ERROR);
        }
        userTenantService.removeUser(userId, tenantId);
        TenantDO tenantDO = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getOwnerId, userId)
                .eq(TenantDO::getType, "PERSONAL"));
        return new TenantLeaveRespDTO(tenantDO.getTenantId());
    }

    @Override
    public TenantMembersListRespDTO listTenantMembers(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_CAN_NOT_LIST_MEMBERS);
        if (!userTenantService.isUserJoinedTenant(userId, tenantId)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        Page<UserTenantDO> pageResult = userTenantService.page(
                new Page<>(pageNum, pageSize),
                Wrappers.lambdaQuery(UserTenantDO.class)
                        .eq(UserTenantDO::getTenantId, tenantId)
                        .isNull(UserTenantDO::getLeftAt)
                        .orderByAsc(UserTenantDO::getJoinedAt));

        List<Long> userIds = pageResult.getRecords().stream()
                .map(UserTenantDO::getUserId)
                .distinct()
                .toList();
        Map<Long, UserDO> userMap = userIds.isEmpty() ? Map.of() : userMapper.selectList(
                Wrappers.lambdaQuery(UserDO.class).in(UserDO::getUserId, userIds))
                .stream()
                .collect(Collectors.toMap(UserDO::getUserId, u -> u));

        List<TenantMembersListRespDTO.TenantMemberInfo> respList = pageResult.getRecords().stream()
                .map(ut -> {
                    TenantMembersListRespDTO.TenantMemberInfo memberInfo = new TenantMembersListRespDTO.TenantMemberInfo();
                    memberInfo.setUserId(ut.getUserId());
                    memberInfo.setRole(ut.getRole());
                    memberInfo.setJoinedAt(ut.getJoinedAt());
                    UserDO userDO = userMap.get(ut.getUserId());
                    if (userDO != null) {
                        memberInfo.setUsername(userDO.getUsername());
                        memberInfo.setNickname(userDO.getNickname());
                    }
                    return memberInfo;
                })
                .toList();

        return new TenantMembersListRespDTO(respList, pageResult.getTotal(), pageResult.getPages(), pageNum, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateMemberRole(Long userId, Long tenantId, Long memberId, TenantMemberRoleUpdateReqDTO requestParam) {
        TenantDO tenantDO = validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_UPDATE);
        Boolean userJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!userJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        Boolean memberJoinedTenant = userTenantService.isUserJoinedTenant(memberId, tenantId);
        if (!memberJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_NOT_JOINED);
        }
        String operatorRole = userTenantService.getRoleByUserIdAndTenantId(userId, tenantId);
        String memberRole = userTenantService.getRoleByUserIdAndTenantId(memberId, tenantId);
        if (RoleEnum.valueOf(memberRole).isNotLowerThan(RoleEnum.valueOf(operatorRole))) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_UPDATE);
        }
        String targetRole = requestParam.getNewRole();
        if (RoleEnum.valueOf(targetRole).isNotLowerThan(RoleEnum.valueOf(operatorRole))) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_UPDATE);
        }
        userTenantService.updateUserRole(memberId, tenantId, memberRole, targetRole);

        String oldRoleName = ROLE_DISPLAY_MAP.getOrDefault(memberRole, memberRole);
        String newRoleName = ROLE_DISPLAY_MAP.getOrDefault(targetRole, targetRole);
        notificationService.publishNotification(
                tenantId, "SYSTEM", "INFO",
                "角色已变更",
                "您在租户「" + tenantDO.getName() + "」中的角色已被管理员从" + oldRoleName + "变更为" + newRoleName,
                null, List.of(memberId));

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeMember(Long userId, Long tenantId, Long memberId) {
        TenantDO tenantDO = validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_REMOVE);
        Boolean userJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!userJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        Boolean memberJoinedTenant = userTenantService.isUserJoinedTenant(memberId, tenantId);
        if (!memberJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_NOT_JOINED);
        }
        String operatorRole = userTenantService.getRoleByUserIdAndTenantId(userId, tenantId);
        String memberRole = userTenantService.getRoleByUserIdAndTenantId(memberId, tenantId);
        if (RoleEnum.valueOf(memberRole).isNotLowerThan(RoleEnum.valueOf(operatorRole))) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_REMOVE);
        }
        userTenantService.removeUser(memberId, tenantId);

        notificationService.publishNotification(
                tenantId, "SYSTEM", "WARNING",
                "已被移出租户",
                "您已被管理员移出租户「" + tenantDO.getName() + "」",
                null, List.of(memberId));

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantJoinRespDTO joinTenant(Long userId, TenantJoinReqDTO requestParam) {
        Long joinedTenantCount = userTenantService.getUserJoinedTenantCount(userId);
        if (joinedTenantCount >= 10) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_COUNT_EXCEEDS);
        }

        // 校验邀请码
        TenantInviteDO inviteDO = inviteService.validateAndGetInviteCode(requestParam.getInviteCode());
        Long tenantId = inviteDO.getTenantId();

        // 用户是否已经加入
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_HAS_BEEN_JOINED);
        }

        // 校验租户
        TenantDO tenantDO = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenantDO == null || !"TEAM".equals(tenantDO.getType())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_JOIN_PROHIBITED);
        }

        // 直接加入模式
        if (tenantDO.getJoinApprovalMode() == 0) {
            joinMember(userId, tenantId);
            inviteService.incrementUsageCount(inviteDO.getId());
            return new TenantJoinRespDTO(false);
        }

        // 审批模式
        approvalService.createJoinRequest(userId, tenantId, inviteDO.getId(), tenantDO.getName());
        return new TenantJoinRespDTO(true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void joinMember(Long userId, Long tenantId) {
        Boolean result = userTenantService.createUserTenant(userId, tenantId, RoleEnum.MEMBER.name());
        if (!result) {
            log.error("Join Tenant Error: tenant {}, user {}", tenantId, userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_JOIN_ERROR);
        }
    }
}
