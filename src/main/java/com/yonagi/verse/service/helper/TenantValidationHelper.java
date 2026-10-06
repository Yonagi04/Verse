package com.yonagi.verse.service.helper;

import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 跨领域租户校验工具类。
 * 供 TenantCrudService、TenantMembershipService、TenantInviteService、TenantApprovalService 共用。
 */
@Component
@RequiredArgsConstructor
public class TenantValidationHelper {

    private final com.yonagi.verse.service.tenant.TenantAccessPolicy tenantAccess;
    private final UserTenantService userTenantService;

    /**
     * 校验租户存在、状态为活跃(1)、类型为 TEAM。
     *
     * @param tenantId     租户业务 ID
     * @param notTeamError 非 TEAM 类型时抛出的错误码
     * @return 租户 DO（供调用方使用，避免重复查询）
     */
    public TenantDO validateTenantTeamActive(Long tenantId, TenantErrorCodeEnum notTeamError) {
        return tenantAccess.requireTeam(tenantId, notTeamError);
    }

    /**
     * 组合校验：租户活跃 + 用户是成员。
     * 适用于成员管理、邀请码管理、审批流等需要验证用户和租户关系的场景。
     *
     * @param userId   用户 ID
     * @param tenantId 租户业务 ID
     */
    public void validateMembership(Long userId, Long tenantId) {
        tenantAccess.requireTeamMember(userId, tenantId);
    }
}
