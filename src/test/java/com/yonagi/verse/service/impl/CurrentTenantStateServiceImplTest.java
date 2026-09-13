package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dao.projection.CurrentTenantState;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentTenantStateServiceImplTest {

    @Test
    void validCurrentTenantDoesNotWriteDatabase() {
        Fixture fixture = fixture();
        CurrentTenantState valid = state(20L, 20L, "ADMIN");
        when(fixture.userMapper.selectCurrentTenantState(10L)).thenReturn(valid);

        assertSame(valid, fixture.service.resolveCurrentTenant(10L));
        verify(fixture.userMapper, never()).compareAndSetCurrentTenant(any(), any(), any());
    }

    @Test
    void invalidCurrentTenantFallsBackToPersonalTenantWithCas() {
        Fixture fixture = fixture();
        when(fixture.userMapper.selectCurrentTenantState(10L)).thenReturn(state(20L, null, null));
        when(fixture.userTenantMapper.selectValidPersonalTenant(10L))
                .thenReturn(state(20L, 30L, "SUPER_ADMIN").setName("个人空间").setType("PERSONAL"));
        when(fixture.userMapper.compareAndSetCurrentTenant(10L, 20L, 30L)).thenReturn(1);

        CurrentTenantState result = fixture.service.resolveCurrentTenant(10L);

        assertEquals(30L, result.getTenantId());
        assertTrue(result.isRepaired());
    }

    @Test
    void missingPersonalTenantClearsInvalidStoredValue() {
        Fixture fixture = fixture();
        when(fixture.userMapper.selectCurrentTenantState(10L)).thenReturn(state(20L, null, null));
        when(fixture.userMapper.compareAndSetCurrentTenant(10L, 20L, null)).thenReturn(1);

        CurrentTenantState result = fixture.service.resolveCurrentTenant(10L);

        assertNull(result.getTenantId());
        assertTrue(result.isRepaired());
    }

    @Test
    void casCompetitionUsesLatestTenantWithoutSecondRepair() {
        Fixture fixture = fixture();
        CurrentTenantState stale = state(20L, null, null);
        CurrentTenantState latest = state(40L, 40L, "MEMBER");
        when(fixture.userMapper.selectCurrentTenantState(10L)).thenReturn(stale, latest);
        when(fixture.userTenantMapper.selectValidPersonalTenant(10L)).thenReturn(state(20L, 30L, "SUPER_ADMIN"));
        when(fixture.userMapper.compareAndSetCurrentTenant(10L, 20L, 30L)).thenReturn(0);

        assertSame(latest, fixture.service.resolveCurrentTenant(10L));
        verify(fixture.userMapper, times(1)).compareAndSetCurrentTenant(any(), any(), any());
    }

    @Test
    void switchUsesTenantMembershipUserLockOrderAndUpdatesBothRows() {
        Fixture fixture = fixture();
        TenantDO tenant = tenant(20L, "TEAM");
        UserTenantDO membership = membership(10L, 20L, "ADMIN");
        when(fixture.tenantMapper.selectActiveTenantForUpdate(20L)).thenReturn(tenant);
        when(fixture.userTenantMapper.selectActiveMembershipForUpdate(10L, 20L)).thenReturn(membership);
        when(fixture.userMapper.selectActiveUserForUpdate(10L)).thenReturn(new UserDO());
        when(fixture.userMapper.updateCurrentTenant(10L, 20L)).thenReturn(1);
        when(fixture.userTenantMapper.touchLastAccessedAt(10L, 20L)).thenReturn(1);

        CurrentTenantState result = fixture.service.switchTenant(10L, 20L);

        assertEquals("ADMIN", result.getRole());
        InOrder order = inOrder(fixture.tenantMapper, fixture.userTenantMapper, fixture.userMapper);
        order.verify(fixture.tenantMapper).selectActiveTenantForUpdate(20L);
        order.verify(fixture.userTenantMapper).selectActiveMembershipForUpdate(10L, 20L);
        order.verify(fixture.userMapper).selectActiveUserForUpdate(10L);
        order.verify(fixture.userMapper).updateCurrentTenant(10L, 20L);
        order.verify(fixture.userTenantMapper).touchLastAccessedAt(10L, 20L);
    }

    @Test
    void repeatedSwitchOnlyRefreshesLastAccessedTime() {
        Fixture fixture = fixture();
        UserDO user = new UserDO();
        user.setLastActiveTenantId(20L);
        when(fixture.tenantMapper.selectActiveTenantForUpdate(20L)).thenReturn(tenant(20L, "TEAM"));
        when(fixture.userTenantMapper.selectActiveMembershipForUpdate(10L, 20L))
                .thenReturn(membership(10L, 20L, "MEMBER"));
        when(fixture.userMapper.selectActiveUserForUpdate(10L)).thenReturn(user);
        when(fixture.userTenantMapper.touchLastAccessedAt(10L, 20L)).thenReturn(1);

        fixture.service.switchTenant(10L, 20L);

        verify(fixture.userMapper, never()).updateCurrentTenant(any(), any());
        verify(fixture.userTenantMapper).touchLastAccessedAt(10L, 20L);
    }

    @Test
    void switchRejectsMissingMembershipWithoutUpdatingUser() {
        Fixture fixture = fixture();
        when(fixture.tenantMapper.selectActiveTenantForUpdate(20L)).thenReturn(tenant(20L, "TEAM"));

        assertThrows(ClientException.class, () -> fixture.service.switchTenant(10L, 20L));
        verify(fixture.userMapper, never()).updateCurrentTenant(any(), any());
    }

    @Test
    void membershipWriteFailureAbortsFallback() {
        Fixture fixture = fixture();
        when(fixture.tenantMapper.selectActiveTenantForUpdate(20L)).thenReturn(tenant(20L, "TEAM"));
        when(fixture.userTenantMapper.selectActiveMembershipForUpdate(10L, 20L))
                .thenReturn(membership(10L, 20L, "MEMBER"));
        when(fixture.userMapper.selectActiveUserForUpdate(10L)).thenReturn(new UserDO());

        assertThrows(ServerException.class,
                () -> fixture.service.removeMembershipAndFallback(10L, 20L));
        verify(fixture.userMapper, never()).fallbackCurrentTenant(any(), any());
    }

    @Test
    void closeRequiresTargetTenantSuperAdminAndBatchFallbacks() {
        Fixture fixture = fixture();
        when(fixture.tenantMapper.selectActiveTenantForUpdate(20L)).thenReturn(tenant(20L, "TEAM"));
        when(fixture.userTenantMapper.selectActiveMembershipsForUpdate(20L))
                .thenReturn(List.of(membership(10L, 20L, "SUPER_ADMIN")));
        when(fixture.tenantMapper.disableTenant(20L)).thenReturn(1);

        fixture.service.closeTenantAndFallback(10L, 20L);

        verify(fixture.userMapper).fallbackUsersFromClosedTenant(20L);
    }

    private static Fixture fixture() {
        UserMapper userMapper = mock(UserMapper.class);
        UserTenantMapper userTenantMapper = mock(UserTenantMapper.class);
        TenantMapper tenantMapper = mock(TenantMapper.class);
        return new Fixture(userMapper, userTenantMapper, tenantMapper,
                new CurrentTenantStateServiceImpl(userMapper, userTenantMapper, tenantMapper));
    }

    private static CurrentTenantState state(Long storedId, Long tenantId, String role) {
        return new CurrentTenantState().setStoredTenantId(storedId).setTenantId(tenantId).setRole(role);
    }

    private static TenantDO tenant(Long tenantId, String type) {
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(tenantId);
        tenant.setName("Verse");
        tenant.setType(type);
        return tenant;
    }

    private static UserTenantDO membership(Long userId, Long tenantId, String role) {
        UserTenantDO membership = new UserTenantDO();
        membership.setUserId(userId);
        membership.setTenantId(tenantId);
        membership.setRole(role);
        return membership;
    }

    private record Fixture(UserMapper userMapper, UserTenantMapper userTenantMapper,
                           TenantMapper tenantMapper, CurrentTenantStateServiceImpl service) {
    }
}
