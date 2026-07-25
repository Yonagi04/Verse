package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.UserTenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/25 10:41
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserTenantServiceImpl extends ServiceImpl<UserTenantMapper, UserTenantDO> implements UserTenantService {

    @Override
    public Boolean createUserTenant(Long userId, Long tenantId) {
        if (userId == null) {
            throw new ClientException(UserTenantErrorCodeEnum.USER_ID_IS_NULL);
        }
        if (tenantId == null) {
            throw new ClientException(UserTenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        UserTenantDO userTenantDO = new UserTenantDO();
        userTenantDO.setTenantId(tenantId);
        userTenantDO.setUserId(userId);
        userTenantDO.setRole(RoleEnum.SUPER_ADMIN.name());
        userTenantDO.setJoinedAt(new Date());
        int userTenantInserted = baseMapper.insert(userTenantDO);
        if (userTenantInserted < 1) {
            log.error("Failed to create user-tenant association for userId: {}, tenantId: {}", userId, tenantId);
            throw new ServerException(UserTenantErrorCodeEnum.USER_TENANT_CREATE_FAILED);
        }
        return Boolean.TRUE;
    }

    @Override
    public String getRoleByUserIdAndTenantId(Long userId, Long tenantId) {
        if (userId == null) {
            throw new ClientException(UserTenantErrorCodeEnum.USER_ID_IS_NULL);
        }
        if (tenantId == null) {
            throw new ClientException(UserTenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        UserTenantDO userTenantDO = baseMapper.selectOne(Wrappers.lambdaQuery(UserTenantDO.class)
                .eq(UserTenantDO::getUserId, userId)
                .eq(UserTenantDO::getTenantId, tenantId)
                .isNull(UserTenantDO::getLeftAt));
        if (userTenantDO == null) {
            log.warn("User-Tenant association not found for userId: {}, tenantId: {}", userId, tenantId);
            throw new ServerException(UserTenantErrorCodeEnum.USER_TENANT_RELATION_NOT_EXIST);
        }
        return userTenantDO.getRole();
    }
}
