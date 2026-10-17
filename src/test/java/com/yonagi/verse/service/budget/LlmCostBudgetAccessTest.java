package com.yonagi.verse.service.budget;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.pricing.PricingConfigurationService;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmCostBudgetAccessTest {
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final LlmCostBudgetMapper budgets = mock(LlmCostBudgetMapper.class);
    private final LlmServiceMapper services = mock(LlmServiceMapper.class);
    private final LlmCostBudgetLedger ledger = mock(LlmCostBudgetLedger.class);
    private final TenantAccessPolicy access = new TenantAccessPolicy(tenants, mock(UserTenantMapper.class));
    private final LlmCostBudgetConfigurationService service = new LlmCostBudgetConfigurationService(
            budgets, services, mock(PricingConfigurationService.class), access, ledger,
            mock(BudgetPeriodResolver.class), users);

    @BeforeEach
    void activeTenantAndUser() {
        when(tenants.selectActiveTenantForShare(2L)).thenReturn(new TenantDO());
        when(users.selectActiveUserForShare(1L)).thenReturn(new UserDO());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void permissionsCoordinateTenantBeforeUserAndMembership(String role) {
        when(budgets.role(2L, 1L)).thenReturn(role);

        assertEquals(role, service.requireMember(2L, 1L));

        var order = inOrder(tenants, users, budgets);
        order.verify(tenants).selectActiveTenantForShare(2L);
        order.verify(users).selectActiveUserForShare(1L);
        order.verify(budgets).role(2L, 1L);
        verifyNoInteractions(services, ledger);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN"})
    void administratorsRetainWritePermission(String role) {
        when(budgets.role(2L, 1L)).thenReturn(role);
        service.requireAdmin(2L, 1L);
        verify(budgets).role(2L, 1L);
    }

    @Test
    void membersCannotEnterBudgetWriteFlow() {
        when(budgets.role(2L, 1L)).thenReturn("MEMBER");
        var failure = assertThrows(ClientException.class, () -> service.update(1L, 2L, 10L, null));
        assertEquals(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED.code(), failure.getErrorCode());
        verifyNoInteractions(services, ledger);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"OWNER", "LEFT"})
    void missingOrInvalidMembershipCannotReadBudgets(String role) {
        when(budgets.role(2L, 1L)).thenReturn(role);
        var failure = assertThrows(ClientException.class, () -> service.status(1L, 2L, 10L));
        assertEquals(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED.code(), failure.getErrorCode());
        verifyNoInteractions(services, ledger);
    }

    @Test
    void inactiveAccountIsDeniedBeforeMembershipOrLedgerReads() {
        when(users.selectActiveUserForShare(1L)).thenReturn(null);
        var failure = assertThrows(ClientException.class, () -> service.status(1L, 2L, 10L));
        assertEquals(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED.code(), failure.getErrorCode());
        verifyNoInteractions(budgets, services, ledger);
    }

    @Test
    void inactiveTenantIsDeniedBeforeUserLock() {
        when(tenants.selectActiveTenantForShare(2L)).thenReturn(null);
        var failure = assertThrows(ClientException.class, () -> service.status(1L, 2L, 10L));
        assertEquals(TenantErrorCodeEnum.TENANT_NOT_EXIST.code(), failure.getErrorCode());
        verifyNoInteractions(users, budgets, services, ledger);
    }

    @Test
    void nullTenantRetainsItsValidationError() {
        var failure = assertThrows(ClientException.class, () -> service.requireMember(null, 1L));
        assertEquals(TenantErrorCodeEnum.TENANT_ID_IS_NULL.code(), failure.getErrorCode());
        verifyNoInteractions(tenants, users, budgets, services, ledger);
    }

    @Test
    void userLockFailurePropagatesWithoutContinuingAuthorization() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(users.selectActiveUserForShare(1L)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.status(1L, 2L, 10L)));
        verifyNoInteractions(budgets, services, ledger);
    }

    @Test
    void membershipFailureDoesNotBecomeAnEmptyBudgetStatus() {
        var failure = new DataAccessResourceFailureException("membership unavailable");
        when(budgets.role(2L, 1L)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.status(1L, 2L, 10L)));
        verifyNoInteractions(services, ledger);
    }
}
