package com.yonagi.verse.service.playground;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.PlaygroundWorkspaceDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.PlaygroundWorkspaceMapper;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Playground 身份、功能与工作区读取资格；缓存和业务入口共用。 */
@Component
@RequiredArgsConstructor
public class PlaygroundAccessPolicy {
    private final TenantAccessPolicy tenants;
    private final PlaygroundWorkspaceMapper workspaces;
    public TenantDO requireTenant(UserContext actor, Long tenantId) {
        if (actor == null || actor.getApiKeyId() != null || actor.getUserId() == null
                || tenantId == null || !tenantId.equals(actor.getCurrentTenantId()))
            throw new ClientException(TenantErrorCodeEnum.TENANT_CONTEXT_MISMATCH);
        return tenants.requireContext(actor, tenantId);
    }
    public TenantDO requireEnabled(UserContext actor, Long tenantId) {
        TenantDO tenant = requireTenant(actor, tenantId);
        if (!Integer.valueOf(1).equals(tenant.getPlaygroundEnabled())) throw new ClientException(PlaygroundErrorCodeEnum.DISABLED);
        return tenant;
    }
    public PlaygroundWorkspaceDO requireWorkspace(UserContext actor, Long tenantId, Long workspaceId) {
        PlaygroundWorkspaceDO workspace = findOwnedWorkspace(actor, tenantId, workspaceId);
        if (workspace == null) throw new ClientException(PlaygroundErrorCodeEnum.SESSION_NOT_FOUND);
        return workspace;
    }
    /** 查询分类允许短期保存空结果，归属约束仍与业务读取共用。 */
    public PlaygroundWorkspaceDO findOwnedWorkspace(UserContext actor, Long tenantId, Long workspaceId) {
        return workspaces.selectOne(Wrappers.lambdaQuery(PlaygroundWorkspaceDO.class)
                .eq(PlaygroundWorkspaceDO::getWorkspaceId, workspaceId).eq(PlaygroundWorkspaceDO::getTenantId, tenantId)
                .eq(PlaygroundWorkspaceDO::getOwnerUserId, actor.getUserId()).eq(PlaygroundWorkspaceDO::getDelFlag, 0));
    }
}
