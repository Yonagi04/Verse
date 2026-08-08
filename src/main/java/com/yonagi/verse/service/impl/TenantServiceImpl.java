package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 租户服务门面层。
 * 将请求委托给领域子服务：TenantCrudService、TenantMembershipService、TenantInviteService、TenantApprovalService。
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/07/19 20:52
 */
@Service
@RequiredArgsConstructor
public class TenantServiceImpl extends ServiceImpl<TenantMapper, TenantDO> implements TenantService {

    private final TenantCrudServiceImpl crudService;
    private final TenantMembershipServiceImpl membershipService;
    private final TenantInviteServiceImpl inviteService;
    private final TenantApprovalServiceImpl approvalService;

    @Override
    public List<TenantInfoListRespDTO> listTenants(Long userId) {
        return crudService.listTenants(userId);
    }

    @Override
    public Boolean createTenant(Long userId, TenantCreateReqDTO requestParam) {
        return crudService.createTenant(userId, requestParam);
    }

    @Override
    public Long createPersonalTenant(Long userId, String tenantName) {
        return crudService.createPersonalTenant(userId, tenantName);
    }

    @Override
    public Boolean updateTenant(Long userId, Long tenantId, TenantUpdateReqDTO requestParam) {
        return crudService.updateTenant(userId, tenantId, requestParam);
    }

    @Override
    public TenantInfoRespDTO getTenantInfo(Long userId, Long tenantId) {
        return crudService.getTenantInfo(userId, tenantId);
    }

    @Override
    public Long getPersonalTenantId(Long userId) {
        return crudService.getPersonalTenantId(userId);
    }

    @Override
    public TenantInviteRespDTO inviteUser(Long userId, Long tenantId, TenantInviteReqDTO requestParam) {
        return inviteService.inviteUser(userId, tenantId, requestParam);
    }

    @Override
    public TenantJoinRespDTO joinTenant(Long userId, TenantJoinReqDTO requestParam) {
        return membershipService.joinTenant(userId, requestParam);
    }

    @Override
    public TenantJoinInfoRespDTO getTenantAndInviteCodeInfo(String inviteCode) {
        return inviteService.getTenantAndInviteCodeInfo(inviteCode);
    }

    @Override
    public TenantLeavePrepareRespDTO prepareLeaveTenant(Long userId, Long tenantId) {
        return membershipService.prepareLeaveTenant(userId, tenantId);
    }

    @Override
    public TenantLeaveRespDTO leaveTenant(Long userId, Long tenantId) {
        return membershipService.leaveTenant(userId, tenantId);
    }

    @Override
    public TenantSwitchRespDTO switchTenant(Long userId, Long tenantId) {
        return crudService.switchTenant(userId, tenantId);
    }

    @Override
    public TenantClosePrepareRespDTO prepareCloseTenant(Long userId, Long tenantId) {
        return crudService.prepareCloseTenant(userId, tenantId);
    }

    @Override
    public Boolean closeTenant(Long userId, Long tenantId, TenantCloseReqDTO requestParam) {
        return crudService.closeTenant(userId, tenantId, requestParam);
    }

    @Override
    public TenantMembersListRespDTO listTenantMembers(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        return membershipService.listTenantMembers(userId, tenantId, pageNum, pageSize);
    }

    @Override
    public Boolean updateMemberRole(Long userId, Long tenantId, Long memberId, TenantMemberRoleUpdateReqDTO requestParam) {
        return membershipService.updateMemberRole(userId, tenantId, memberId, requestParam);
    }

    @Override
    public Boolean removeMember(Long userId, Long tenantId, Long memberId) {
        return membershipService.removeMember(userId, tenantId, memberId);
    }

    @Override
    public TenantInviteListRespDTO listTenantInviteCodes(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        return inviteService.listTenantInviteCodes(userId, tenantId, pageNum, pageSize);
    }

    @Override
    public Boolean deactivateInviteCode(Long userId, Long tenantId, Long inviteCodeId) {
        return inviteService.deactivateInviteCode(userId, tenantId, inviteCodeId);
    }

    @Override
    public Boolean activateInviteCode(Long userId, Long tenantId, Long inviteCodeId) {
        return inviteService.activateInviteCode(userId, tenantId, inviteCodeId);
    }

    @Override
    public TenantJoinReqListRespDTO listJoinRequests(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        return approvalService.listJoinRequests(userId, tenantId, pageNum, pageSize);
    }

    @Override
    public Boolean approveJoinRequest(Long userId, Long tenantId, Long requestId) {
        return approvalService.approveJoinRequest(userId, tenantId, requestId);
    }

    @Override
    public Boolean rejectJoinRequest(Long userId, Long tenantId, Long requestId, TenantJoinRejectReqDTO requestParam) {
        return approvalService.rejectJoinRequest(userId, tenantId, requestId, requestParam);
    }

    @Override
    public Long getUnreviewedJoinReqCount(Long userId, Long tenantId) {
        return approvalService.getUnreviewedJoinReqCount(userId, tenantId);
    }

    @Override
    public Boolean sendNotificationInTenant(Long userId, Long tenantId, TenantSendNotificationReqDTO requestParam) {
        return crudService.sendNotificationInTenant(userId, tenantId, requestParam);
    }
}
