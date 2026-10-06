package com.yonagi.verse.common.cache;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.tenant.*;
import com.yonagi.verse.service.playground.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 使用真实领域规则，验证缓存适配不复制或放宽业务资格。 */
class QueryAccessPoliciesTest {
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final UserTenantMapper members = mock(UserTenantMapper.class);
    private final TenantInviteMapper invites = mock(TenantInviteMapper.class);
    private final PlaygroundWorkspaceMapper workspaces = mock(PlaygroundWorkspaceMapper.class);
    private final TenantAccessPolicy tenantAccess = new TenantAccessPolicy(tenants, members);
    private final PlaygroundAccessPolicy playground = new PlaygroundAccessPolicy(tenantAccess, workspaces);
    private final UserContext actor = new UserContext().setUserId(10L).setCurrentTenantId(20L).setRole("ADMIN");
    @BeforeAll static void metadata() {
        for (Class<?> type : List.of(TenantDO.class, UserTenantDO.class, TenantInviteDO.class, PlaygroundWorkspaceDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "cache-access"), type);
    }
    @BeforeEach void active() {
        TenantDO tenant = new TenantDO(); tenant.setType("TEAM"); tenant.setPlaygroundEnabled(1);
        when(tenants.selectOne(any())).thenReturn(tenant);
        UserTenantDO relation = new UserTenantDO(); relation.setRole("ADMIN");
        when(members.selectOne(any())).thenReturn(relation);
    }
    @Test void removedMembershipOrChangedRoleCannotReadWarmResults() {
        var query = new TenantQueryAccess(tenantAccess);
        query.check(new Object[]{actor, 20L});
        UserTenantDO relation = new UserTenantDO(); relation.setRole("MEMBER");
        when(members.selectOne(any())).thenReturn(relation);
        assertThrows(ClientException.class, () -> query.check(new Object[]{actor, 20L}));
        when(members.selectOne(any())).thenReturn(null);
        assertThrows(ClientException.class, () -> query.check(new Object[]{actor, 20L}));
    }
    @Test void playgroundCacheAndBusinessRulesRejectApiKeyAndWrongCurrentTenant() {
        var query = new PlaygroundQueryAccess(playground);
        query.check(new Object[]{actor, 20L});
        actor.setApiKeyId(99L);
        assertEquals(com.yonagi.verse.common.enums.TenantErrorCodeEnum.TENANT_CONTEXT_MISMATCH.code(), assertThrows(ClientException.class, () -> query.check(new Object[]{actor, 20L})).getErrorCode());
        assertThrows(ClientException.class, () -> playground.requireEnabled(actor, 20L));
        actor.setApiKeyId(null).setCurrentTenantId(21L);
        assertThrows(ClientException.class, () -> query.check(new Object[]{actor, 20L}));
        assertThrows(ClientException.class, () -> playground.requireEnabled(actor, 20L));
    }
    @Test void ordinaryTenantQueriesStillAllowJoinedTargetOutsideCurrentTenant() {
        actor.setCurrentTenantId(21L);
        assertDoesNotThrow(() -> new TenantQueryAccess(tenantAccess).check(new Object[]{actor, 20L}));
    }
    @Test void disabledTenantAndPlaygroundAreRejected() {
        TenantDO disabled = new TenantDO(); disabled.setPlaygroundEnabled(0);
        when(tenants.selectOne(any())).thenReturn(disabled);
        assertThrows(ClientException.class, () -> new PlaygroundQueryAccess(playground).check(new Object[]{actor, 20L}));
        when(tenants.selectOne(any())).thenReturn(null);
        assertThrows(ClientException.class, () -> new PlaygroundQueryAccess(playground).check(new Object[]{actor, 20L}));
    }
    @Test void expiredAndDeactivatedInvitesUseSameRuleForPreviewAndJoin() {
        var access = new TenantInviteAccessPolicy(invites, tenantAccess);
        TenantInviteDO invite = new TenantInviteDO(); invite.setTenantId(20L); invite.setIsActive(1);
        invite.setExpiresAt(new Date(System.currentTimeMillis() - 1000));
        when(invites.selectOne(any())).thenReturn(invite);
        assertThrows(ClientException.class, () -> access.check(new Object[]{"code"}));
        assertThrows(ClientException.class, () -> access.requireValidCode("code"));
        invite.setExpiresAt(null); invite.setIsActive(0);
        assertThrows(ClientException.class, () -> access.check(new Object[]{"code"}));
    }
    @Test void overviewKeyChangesWhenMembershipOrRoleChanges() {
        var access = new OverviewQueryAccess(members);
        UserTenantDO relation = new UserTenantDO(); relation.setTenantId(20L); relation.setRole("ADMIN");
        when(members.selectOverviewMemberships(10L)).thenReturn(List.of(relation));
        List<Object> before = new ArrayList<>(); access.contributeParameters(before, new Object[]{10L});
        relation.setRole("MEMBER");
        List<Object> after = new ArrayList<>(); access.contributeParameters(after, new Object[]{10L});
        assertNotEquals(before, after);
        when(members.selectOverviewMemberships(10L)).thenReturn(List.of());
        after.clear(); access.contributeParameters(after, new Object[]{10L});
        assertNotEquals(before, after);
    }
    @Test void privatePresetOwnershipIsRechecked() {
        assertThrows(ClientException.class, () -> playground.requireWorkspace(actor, 20L, 30L));
        verify(workspaces).selectOne(any());
    }
}
