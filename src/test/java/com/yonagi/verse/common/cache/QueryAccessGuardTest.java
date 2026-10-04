package com.yonagi.verse.common.cache;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class QueryAccessGuardTest {
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final UserTenantMapper members = mock(UserTenantMapper.class);
    private final TenantInviteMapper invites = mock(TenantInviteMapper.class);
    private final PlaygroundWorkspaceMapper workspaces = mock(PlaygroundWorkspaceMapper.class);
    private final QueryAccessGuard guard = new QueryAccessGuard(mock(QueryCache.class), tenants, members, invites,
            workspaces, mock(org.springframework.beans.factory.ObjectProvider.class));
    private final UserContext actor = new UserContext().setUserId(10L).setCurrentTenantId(20L).setRole("ADMIN");
    @BeforeAll static void metadata() {
        for (Class<?> type : java.util.List.of(TenantDO.class, UserTenantDO.class, TenantInviteDO.class, PlaygroundWorkspaceDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "cache-guard"), type);
    }
    @BeforeEach void active() {
        TenantDO tenant = new TenantDO(); tenant.setType("TEAM"); tenant.setPlaygroundEnabled(1);
        when(tenants.selectOne(any())).thenReturn(tenant);
        UserTenantDO relation = new UserTenantDO(); relation.setRole("ADMIN");
        when(members.selectActiveMembership(10L, 20L)).thenReturn(relation);
    }
    @Test void removedMemberOrChangedRoleCannotReadWarmResult() {
        var policy = QueryCacheTestSupport.policy("UsageReportServiceImpl", "dashboard");
        guard.check(policy, new Object[]{actor, 20L});
        UserTenantDO relation = new UserTenantDO(); relation.setRole("MEMBER");
        when(members.selectActiveMembership(10L, 20L)).thenReturn(relation);
        assertThrows(ClientException.class, () -> guard.check(policy, new Object[]{actor, 20L}));
        when(members.selectActiveMembership(10L, 20L)).thenReturn(null);
        assertThrows(ClientException.class, () -> guard.check(policy, new Object[]{actor, 20L}));
    }
    @Test void disabledTenantAndPlaygroundCannotReadWarmResult() {
        var policy = QueryCacheTestSupport.policy("PlaygroundServiceImpl", "models");
        guard.check(policy, new Object[]{actor, 20L});
        TenantDO disabled = new TenantDO(); disabled.setPlaygroundEnabled(0);
        when(tenants.selectOne(any())).thenReturn(disabled);
        assertThrows(ClientException.class, () -> guard.check(policy, new Object[]{actor, 20L}));
        when(tenants.selectOne(any())).thenReturn(null);
        assertThrows(ClientException.class, () -> guard.check(policy, new Object[]{actor, 20L}));
    }
    @Test void expiredOrDeactivatedInviteIsRejectedEvenWithoutTableWrite() {
        var policy = QueryCacheTestSupport.policy("TenantInviteServiceImpl", "getTenantAndInviteCodeInfo");
        TenantInviteDO invite = new TenantInviteDO(); invite.setTenantId(20L); invite.setIsActive(1);
        invite.setExpiresAt(new java.util.Date(System.currentTimeMillis()-1000));
        when(invites.selectOne(any())).thenReturn(invite);
        assertThrows(ClientException.class, () -> guard.check(policy, new Object[]{"code"}));
        invite.setExpiresAt(null); invite.setIsActive(0);
        assertThrows(ClientException.class, () -> guard.check(policy, new Object[]{"code"}));
    }
    @Test void overviewCacheIdentityChangesWhenMembershipIsRemovedOrRoleChanges() {
        QueryCache budget = mock(QueryCache.class);
        when(budget.check(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        var live = new QueryAccessGuard(budget, tenants, members, invites, workspaces, mock(org.springframework.beans.factory.ObjectProvider.class));
        UserTenantDO relation = new UserTenantDO(); relation.setTenantId(20L); relation.setRole("ADMIN");
        when(members.selectOverviewMemberships(10L)).thenReturn(java.util.List.of(relation));
        Object admin = live.batchIdentity(new Object[]{10L});
        relation.setRole("MEMBER");
        assertNotEquals(admin, live.batchIdentity(new Object[]{10L}));
        when(members.selectOverviewMemberships(10L)).thenReturn(java.util.List.of());
        assertNotEquals(admin, live.batchIdentity(new Object[]{10L}));
    }

    @Test void privatePresetOwnershipIsRechecked() {
        var policy = QueryCacheTestSupport.policy("PlaygroundWorkbenchServiceImpl", "detail");
        guard.check(policy, new Object[]{actor, 20L, 30L});
        assertThrows(ClientException.class, () -> guard.checkWorkspace(new Object[]{actor, 20L, 30L}));
        verify(workspaces).selectOne(any());
    }
}
