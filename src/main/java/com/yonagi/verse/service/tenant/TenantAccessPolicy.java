package com.yonagi.verse.service.tenant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 租户读取资格的权威实现，跨当前租户读取由调用业务决定。 */
@Component
@RequiredArgsConstructor
public class TenantAccessPolicy {
    private final TenantMapper tenants;
    private final UserTenantMapper memberships;

    public TenantDO activeTenant(Long tenantId) {
        if (tenantId == null) throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        TenantDO tenant = tenants.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId).eq(TenantDO::getStatus, 1).eq(TenantDO::getDelFlag, 0));
        if (tenant == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        return tenant;
    }

    public UserTenantDO membership(Long userId, Long tenantId) {
        if (userId == null) throw new ClientException(TenantErrorCodeEnum.USER_ID_IS_NULL);
        if (tenantId == null) throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        UserTenantDO relation = memberships.selectOne(Wrappers.lambdaQuery(UserTenantDO.class)
                .eq(UserTenantDO::getUserId, userId).eq(UserTenantDO::getTenantId, tenantId)
                .isNull(UserTenantDO::getLeftAt).in(UserTenantDO::getRole, "MEMBER", "ADMIN", "SUPER_ADMIN"));
        if (relation == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        return relation;
    }

    public TenantDO requireMember(Long userId, Long tenantId) {
        TenantDO tenant = activeTenant(tenantId);
        membership(userId, tenantId);
        return tenant;
    }

    public TenantDO requireContext(UserContext actor, Long tenantId) {
        if (actor == null) throw new ClientException(TenantErrorCodeEnum.TENANT_CONTEXT_MISMATCH);
        TenantDO tenant = activeTenant(tenantId);
        UserTenantDO relation = membership(actor.getUserId(), tenantId);
        if (actor.getRole() != null && !actor.getRole().equals(relation.getRole()))
            throw new ClientException(TenantErrorCodeEnum.TENANT_CONTEXT_MISMATCH);
        return tenant;
    }

    public TenantDO requireTeamMember(Long userId, Long tenantId) {
        TenantDO tenant = requireTeam(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        membership(userId, tenantId);
        return tenant;
    }

    public TenantDO requireTeam(Long tenantId, TenantErrorCodeEnum errorCode) {
        TenantDO tenant = activeTenant(tenantId);
        if (!"TEAM".equals(tenant.getType())) throw new ClientException(errorCode);
        return tenant;
    }
}
