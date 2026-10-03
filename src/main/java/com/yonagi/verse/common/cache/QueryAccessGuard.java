package com.yonagi.verse.common.cache;

import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.UsageReportService;
import com.yonagi.verse.common.enums.UsageGranularity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 命中结果缓存也必须实时校验目标租户、成员角色及业务开关。 */
@Component
@RequiredArgsConstructor
public class QueryAccessGuard {
    private final QueryCache cache;
    private final TenantMapper tenants;
    private final UserTenantMapper memberships;
    private final TenantInviteMapper invites;
    private final PlaygroundWorkspaceMapper workspaces;
    private final org.springframework.beans.factory.ObjectProvider<UsageReportService> reports;

    public void check(QueryCatalogue.Policy policy, Object[] arguments) {
        var access = policy.access();
        if (access == QueryCatalogue.Access.NONE) return;
        if (access == QueryCatalogue.Access.BATCH) {
            // 批量成员与角色已在生成缓存键时实时读取，不再查询后丢弃结果。
            return;
        }
        if (access == QueryCatalogue.Access.INVITE) {
            TenantInviteDO invite = invites.selectOne(Wrappers.lambdaQuery(TenantInviteDO.class)
                    .eq(TenantInviteDO::getCode, arguments[0]));
            if (invite == null || !Integer.valueOf(1).equals(invite.getIsActive())
                    || invite.getExpiresAt() != null && !invite.getExpiresAt().after(new java.util.Date()))
                throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
            activeTenant(invite.getTenantId());
            return;
        }
        Long user = arguments[0] instanceof UserContext c ? c.getUserId() : (Long) arguments[0];
        Long tenantId = (Long) arguments[1];
        TenantDO tenant = activeTenant(tenantId);
        UserTenantDO relation = memberships.selectActiveMembership(user, tenantId);
        if (relation == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        if (arguments[0] instanceof UserContext c && c.getRole() != null && !c.getRole().equals(relation.getRole()))
            throw new ClientException(TenantErrorCodeEnum.TENANT_CONTEXT_MISMATCH);
        if (access == QueryCatalogue.Access.TEAM && !"TEAM".equals(tenant.getType()))
            throw new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        if (access == QueryCatalogue.Access.ADMIN && "MEMBER".equals(relation.getRole()))
            throw new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        if (access == QueryCatalogue.Access.PLAYGROUND && !Integer.valueOf(1).equals(tenant.getPlaygroundEnabled()))
            throw new ClientException(PlaygroundErrorCodeEnum.DISABLED);
        if (access == QueryCatalogue.Access.ACTIVITY && !Integer.valueOf(1).equals(tenant.getActivityRecordingEnabled()))
            throw new ClientException(TenantErrorCodeEnum.TENANT_ACTIVITY_RECORDING_DISABLED);
        if (access == QueryCatalogue.Access.PLAYGROUND && policy.name().endsWith(".detail")) {
            PlaygroundWorkspaceDO workspace = workspaces.selectOne(Wrappers.lambdaQuery(PlaygroundWorkspaceDO.class)
                    .eq(PlaygroundWorkspaceDO::getWorkspaceId, arguments[2])
                    .eq(PlaygroundWorkspaceDO::getTenantId, tenantId).eq(PlaygroundWorkspaceDO::getOwnerUserId, user)
                    .eq(PlaygroundWorkspaceDO::getDelFlag, 0));
            if (workspace == null) throw new ClientException(PlaygroundErrorCodeEnum.SESSION_NOT_FOUND);
        }
        if (access == QueryCatalogue.Access.REPORT && arguments.length >= 6) {
            reports.getObject().resolveFilter((UserContext) arguments[0], tenantId, (UsageGranularity) arguments[2],
                    (java.time.LocalDateTime) arguments[3], (java.time.LocalDateTime) arguments[4], (Long) arguments[5],
                    arguments.length > 6 ? (Long) arguments[6] : null,
                    arguments.length > 7 ? (Long) arguments[7] : null);
        }
    }

    public Object batchIdentity(Object[] arguments) {
        return cache.check(() -> memberships.selectOverviewMemberships((Long) arguments[0]).stream()
                .map(relation -> java.util.Map.of("tenant", relation.getTenantId(), "role", relation.getRole())).toList());
    }

    public boolean isPreset(Object[] arguments) {
        UserContext actor = (UserContext) arguments[0];
        // 分类查询也防穿透；生成会话仍读取实时状态，预设才缓存完整详情。
        String kind = cache.read("workbench-kind", RedisKeyConstant.PLAYGROUND_WORKBENCH_KIND_KEY, java.util.List.of(actor.getUserId(), arguments[1], arguments[2]),
                String.class, java.util.List.of("t_playground_workspace"), java.util.concurrent.TimeUnit.SECONDS.toMillis(QueryCacheTtl.HOURS_4), () -> {
                    PlaygroundWorkspaceDO workspace = workspaces.selectOne(Wrappers.lambdaQuery(PlaygroundWorkspaceDO.class)
                            .eq(PlaygroundWorkspaceDO::getWorkspaceId, arguments[2])
                            .eq(PlaygroundWorkspaceDO::getTenantId, arguments[1]).eq(PlaygroundWorkspaceDO::getOwnerUserId, actor.getUserId())
                            .eq(PlaygroundWorkspaceDO::getDelFlag, 0));
                    return workspace == null ? null : workspace.getKind();
                });
        if (kind == null) throw new ClientException(PlaygroundErrorCodeEnum.SESSION_NOT_FOUND);
        return "PRESET".equals(kind);
    }

    private TenantDO activeTenant(Long tenantId) {
        if (tenantId == null) throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        TenantDO tenant = tenants.selectOne(Wrappers.lambdaQuery(TenantDO.class).eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1).eq(TenantDO::getDelFlag, 0));
        if (tenant == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        return tenant;
    }
}
