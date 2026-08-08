package com.yonagi.verse.service;

import com.yonagi.verse.dto.req.TenantJoinReqDTO;
import com.yonagi.verse.dto.req.TenantMemberRoleUpdateReqDTO;
import com.yonagi.verse.dto.resp.*;

/**
 * 成员管理服务接口。
 */
public interface TenantMembershipService {

    TenantLeavePrepareRespDTO prepareLeaveTenant(Long userId, Long tenantId);

    TenantLeaveRespDTO leaveTenant(Long userId, Long tenantId);

    TenantMembersListRespDTO listTenantMembers(Long userId, Long tenantId, Integer pageNum, Integer pageSize);

    Boolean updateMemberRole(Long userId, Long tenantId, Long memberId, TenantMemberRoleUpdateReqDTO requestParam);

    Boolean removeMember(Long userId, Long tenantId, Long memberId);

    TenantJoinRespDTO joinTenant(Long userId, TenantJoinReqDTO requestParam);

    /** 将用户加入租户为 MEMBER（供审批通过等内部流程调用） */
    void joinMember(Long userId, Long tenantId);
}
