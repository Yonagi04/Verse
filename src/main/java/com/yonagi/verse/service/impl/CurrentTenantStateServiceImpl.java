package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dao.projection.CurrentTenantState;
import com.yonagi.verse.service.CurrentTenantStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以数据库为权威来源解析和维护当前租户，避免认证链路依赖租户门面形成循环依赖。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CurrentTenantStateServiceImpl implements CurrentTenantStateService {

    private final UserMapper userMapper;
    private final UserTenantMapper userTenantMapper;
    private final TenantMapper tenantMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CurrentTenantState resolveCurrentTenant(Long userId) {
        CurrentTenantState state = userMapper.selectCurrentTenantState(userId);
        if (state == null || state.isValid()) {
            return state != null ? state : emptyState();
        }

        CurrentTenantState fallback = userTenantMapper.selectValidPersonalTenant(userId);
        Long fallbackTenantId = fallback != null ? fallback.getTenantId() : null;

        // 空值且没有个人租户时无需执行无意义更新。
        if (state.getStoredTenantId() == null && fallbackTenantId == null) {
            return emptyState();
        }

        int updated = userMapper.compareAndSetCurrentTenant(
                userId, state.getStoredTenantId(), fallbackTenantId);
        if (updated > 0) {
            if (fallback == null) {
                return emptyState().setRepaired(true);
            }
            fallback.setStoredTenantId(fallbackTenantId).setRepaired(true);
            return fallback;
        }

        // 并发切换赢得 CAS 后只有限重读一次，绝不以旧快照覆盖新选择。
        CurrentTenantState latest = userMapper.selectCurrentTenantState(userId);
        if (latest != null && latest.isValid()) {
            return latest;
        }
        log.warn("当前租户自动修复发生竞争且最新状态仍无效: userId={}", userId);
        return emptyState();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CurrentTenantState switchTenant(Long userId, Long tenantId) {
        // 全部租户状态写事务固定按：租户行 -> 成员关系行 -> 用户行 加锁。
        TenantDO tenant = tenantMapper.selectActiveTenantForUpdate(tenantId);
        if (tenant == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        UserTenantDO membership = userTenantMapper.selectActiveMembershipForUpdate(userId, tenantId);
        if (membership == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        UserDO user = userMapper.selectActiveUserForUpdate(userId);
        if (user == null) {
            throw new ClientException(TenantErrorCodeEnum.USER_ID_IS_NULL);
        }
        if ((!tenantId.equals(user.getLastActiveTenantId())
                && userMapper.updateCurrentTenant(userId, tenantId) < 1)
                || userTenantMapper.touchLastAccessedAt(userId, tenantId) < 1) {
            throw new ServerException(TenantErrorCodeEnum.TENANT_SWITCH_ERROR);
        }
        return new CurrentTenantState()
                .setStoredTenantId(tenantId)
                .setTenantId(tenantId)
                .setName(tenant.getName())
                .setType(tenant.getType())
                .setRole(membership.getRole());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long removeMembershipAndFallback(Long userId, Long tenantId) {
        TenantDO tenant = tenantMapper.selectActiveTenantForUpdate(tenantId);
        if (tenant == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        UserTenantDO membership = userTenantMapper.selectActiveMembershipForUpdate(userId, tenantId);
        if (membership == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        UserDO user = userMapper.selectActiveUserForUpdate(userId);
        if (user == null || userTenantMapper.markMembershipLeft(userId, tenantId) < 1) {
            throw new ServerException(TenantErrorCodeEnum.TENANT_LEAVE_ERROR);
        }
        userMapper.fallbackCurrentTenant(userId, tenantId);
        CurrentTenantState personal = userTenantMapper.selectValidPersonalTenant(userId);
        return personal != null ? personal.getTenantId() : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantDO closeTenantAndFallback(Long operatorUserId, Long tenantId) {
        TenantDO tenant = tenantMapper.selectActiveTenantForUpdate(tenantId);
        if (tenant == null || !"TEAM".equals(tenant.getType())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_CAN_NOT_CLOSE);
        }
        var memberships = userTenantMapper.selectActiveMembershipsForUpdate(tenantId);
        UserTenantDO operator = memberships.stream()
                .filter(item -> operatorUserId.equals(item.getUserId()))
                .findFirst().orElse(null);
        if (operator == null || !RoleEnum.SUPER_ADMIN.name().equals(operator.getRole())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        }
        if (tenantMapper.disableTenant(tenantId) < 1) {
            throw new ServerException(TenantErrorCodeEnum.TENANT_CLOSE_ERROR);
        }
        userMapper.fallbackUsersFromClosedTenant(tenantId);
        return tenant;
    }

    private CurrentTenantState emptyState() {
        return new CurrentTenantState();
    }
}
