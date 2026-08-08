package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/19 20:51
 */
public interface TenantService extends IService<TenantDO> {

    List<TenantInfoListRespDTO> listTenants(Long userId);

    Boolean createTenant(Long userId, TenantCreateReqDTO requestParam);

    Long createPersonalTenant(Long userId, String tenantName);

    Boolean updateTenant(Long userId, Long tenantId, TenantUpdateReqDTO requestParam);

    TenantInfoRespDTO getTenantInfo(Long userId, Long tenantId);

    Long getPersonalTenantId(Long userId);

    TenantInviteRespDTO inviteUser(Long userId, Long tenantId, TenantInviteReqDTO requestParam);

    TenantJoinRespDTO joinTenant(Long userId, TenantJoinReqDTO requestParam);

    TenantJoinInfoRespDTO getTenantAndInviteCodeInfo(String inviteCode);

    TenantLeavePrepareRespDTO prepareLeaveTenant(Long userId, Long tenantId);

    TenantLeaveRespDTO leaveTenant(Long userId, Long tenantId);

    TenantSwitchRespDTO switchTenant(Long userId, Long tenantId);

    TenantClosePrepareRespDTO prepareCloseTenant(Long userId, Long tenantId);

    Boolean closeTenant(Long userId, Long tenantId, TenantCloseReqDTO requestParam);

    TenantMembersListRespDTO listTenantMembers(Long userId, Long tenantId, Integer pageNum, Integer pageSize);

    Boolean updateMemberRole(Long userId, Long tenantId, Long memberId, TenantMemberRoleUpdateReqDTO requestParam);

    Boolean removeMember(Long userId, Long tenantId, Long memberId);

    TenantInviteListRespDTO listTenantInviteCodes(Long userId, Long tenantId, Integer pageNum, Integer pageSize);

    Boolean deactivateInviteCode(Long userId, Long tenantId, Long inviteCodeId);

    Boolean activateInviteCode(Long userId, Long tenantId, Long inviteCodeId);

    TenantJoinReqListRespDTO listJoinRequests(Long userId, Long tenantId, Integer pageNum, Integer pageSize);

    Boolean approveJoinRequest(Long userId, Long tenantId, Long requestId);

    Boolean rejectJoinRequest(Long userId, Long tenantId, Long requestId, TenantJoinRejectReqDTO requestParam);

    Long getUnreviewedJoinReqCount(Long userId, Long tenantId);
}
