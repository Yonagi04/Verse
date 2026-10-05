package com.yonagi.verse.service.impl;

import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantActivityTargetType;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 超级管理员交接独立用例；实时授权不缓存，沿用租户行、成员行、用户行的锁顺序。 */
@Service
@RequiredArgsConstructor
public class TenantAdminTransferService {
    private final TenantMapper tenantMapper;
    private final UserTenantMapper membershipMapper;
    private final UserMapper userMapper;
    private final TenantActivityRecorder activityRecorder;

    @Transactional(rollbackFor = Exception.class)
    public Boolean transfer(Long operatorId, Long tenantId, Long targetId) {
        if (operatorId == null || tenantId == null || targetId == null || operatorId.equals(targetId)) {
            throw invalidTransfer();
        }
        TenantDO tenant = tenantMapper.selectActiveTenantForUpdate(tenantId);
        if (tenant == null || !"TEAM".equals(tenant.getType()) || !operatorId.equals(tenant.getOwnerId())) {
            throw invalidTransfer();
        }
        UserTenantDO operator = membershipMapper.selectActiveMembershipForUpdate(operatorId, tenantId);
        UserTenantDO target = membershipMapper.selectActiveMembershipForUpdate(targetId, tenantId);
        if (operator == null || !RoleEnum.SUPER_ADMIN.name().equals(operator.getRole())
                || target == null || !RoleEnum.ADMIN.name().equals(target.getRole())) {
            throw invalidTransfer();
        }
        UserDO targetUser = userMapper.selectActiveUserForUpdate(targetId);
        if (targetUser == null) {
            throw invalidTransfer();
        }
        if (membershipMapper.transferSuperAdminRoles(tenantId, operatorId, targetId) != 2
                || tenantMapper.transferOwner(tenantId, operatorId, targetId) != 1) {
            throw new ServerException(TenantErrorCodeEnum.TENANT_ADMIN_TRANSFER_FAILED);
        }
        // 复用已有角色变更动态及事务 Outbox；两条记录和权限写入一起提交或回滚。
        activityRecorder.record(tenantId, TenantActivityDraft.of(TenantActivityType.MEMBER_ROLE_CHANGED)
                .actor(operatorId).target(TenantActivityTargetType.MEMBER, operatorId, null)
                .detail("oldRole", "SUPER_ADMIN").detail("newRole", "ADMIN"));
        String targetName = targetUser.getNickname() == null || targetUser.getNickname().isBlank()
                ? targetUser.getUsername() : targetUser.getNickname();
        activityRecorder.record(tenantId, TenantActivityDraft.of(TenantActivityType.MEMBER_ROLE_CHANGED)
                .actor(operatorId).target(TenantActivityTargetType.MEMBER, targetId, targetName)
                .detail("oldRole", "ADMIN").detail("newRole", "SUPER_ADMIN"));
        return Boolean.TRUE;
    }

    private ClientException invalidTransfer() {
        return new ClientException(TenantErrorCodeEnum.TENANT_ADMIN_TRANSFER_INVALID);
    }
}
