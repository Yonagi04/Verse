package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.dao.mapper.TenantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantAccountClosurePolicyTest {
    @Mock private TenantMapper tenants;
    @InjectMocks private TenantCrudServiceImpl service;

    @Test
    void prelockOrdersCandidatesAndRetainsOnlyConfirmedOwnership() {
        when(tenants.selectOwnedTenantIdsForClosure(1L)).thenReturn(List.of(30L, 10L, 20L, 10L));
        when(tenants.lockOwnedTenantForClosure(1L, 10L)).thenReturn(10L);
        when(tenants.lockOwnedTenantForClosure(1L, 20L)).thenReturn(null);
        when(tenants.lockOwnedTenantForClosure(1L, 30L)).thenReturn(30L);

        assertEquals(List.of(10L, 30L), service.lockOwnedTenantsForAccountClosure(1L));

        var order = inOrder(tenants);
        order.verify(tenants).selectOwnedTenantIdsForClosure(1L);
        order.verify(tenants).lockOwnedTenantForClosure(1L, 10L);
        order.verify(tenants).lockOwnedTenantForClosure(1L, 20L);
        order.verify(tenants).lockOwnedTenantForClosure(1L, 30L);
        verifyNoMoreInteractions(tenants);
    }

    @Test
    void newCandidateAfterUserLockIsRejectedWithoutAcquiringMoreLocks() {
        when(tenants.selectOwnedTenantIdsForClosure(1L)).thenReturn(List.of(10L, 20L));
        var failure = assertThrows(ClientException.class,
                () -> service.validateAccountClosureTenantLocks(1L, List.of(10L)));
        assertEquals(UserErrorCodeEnum.USER_ACCOUNT_CLOSURE_TENANTS_CHANGED.code(), failure.getErrorCode());
        verify(tenants).selectOwnedTenantIdsForClosure(1L);
        verifyNoMoreInteractions(tenants);
    }

    @Test
    void secondCandidateReadCannotReuseThePrelockSnapshot() {
        when(tenants.selectOwnedTenantIdsForClosure(1L)).thenReturn(List.of(10L), List.of(10L, 20L));
        when(tenants.lockOwnedTenantForClosure(1L, 10L)).thenReturn(10L);
        var locked = service.lockOwnedTenantsForAccountClosure(1L);
        assertThrows(ClientException.class, () -> service.validateAccountClosureTenantLocks(1L, locked));
        verify(tenants, times(2)).selectOwnedTenantIdsForClosure(1L);
        verify(tenants, never()).lockOwnedTenantForClosure(1L, 20L);
        verify(tenants, never()).deleteClosedUsersLockedTenant(any(), any());
    }

    @Test
    void removedCandidatesDoNotRequireNewLocks() {
        when(tenants.selectOwnedTenantIdsForClosure(1L)).thenReturn(List.of(10L));
        service.validateAccountClosureTenantLocks(1L, List.of(10L, 20L));
        verify(tenants).selectOwnedTenantIdsForClosure(1L);
        verifyNoMoreInteractions(tenants);
    }

    @Test
    void synchronousCleanupTouchesOnlySuppliedIdsInOrder() {
        service.deleteClosedUsersLockedTenants(1L, List.of(20L, 10L, 20L));
        var order = inOrder(tenants);
        order.verify(tenants).deleteClosedUsersLockedTenant(1L, 10L);
        order.verify(tenants).deleteClosedUsersLockedTenant(1L, 20L);
        verifyNoMoreInteractions(tenants);
    }

    @Test
    void emptyCandidatesDoNotTriggerAnUnboundedCleanup() {
        when(tenants.selectOwnedTenantIdsForClosure(1L)).thenReturn(List.of());
        assertEquals(List.of(), service.lockOwnedTenantsForAccountClosure(1L));
        service.deleteClosedUsersLockedTenants(1L, List.of());
        verify(tenants).selectOwnedTenantIdsForClosure(1L);
        verifyNoMoreInteractions(tenants);
    }

    @Test
    void cleanupFailureStopsAndPropagatesToTheAccountTransaction() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(tenants.deleteClosedUsersLockedTenant(1L, 10L)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.deleteClosedUsersLockedTenants(1L, List.of(10L, 20L))));
        verify(tenants, never()).deleteClosedUsersLockedTenant(1L, 20L);
    }
}
