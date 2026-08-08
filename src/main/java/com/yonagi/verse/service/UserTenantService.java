package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.UserTenantDO;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/25 10:15
 */
public interface UserTenantService extends IService<UserTenantDO> {

    Boolean createUserTenant(Long userId, Long tenantId, String role);

    String getRoleByUserIdAndTenantId(Long userId, Long tenantId);

    Long getUserJoinedTenantCount(Long userId);

    List<UserTenantDO> getUserTenantList(Long userId, Boolean isAsc, Long limit);

    Boolean isUserJoinedTenant(Long userId, Long tenantId);

    void switchTenant(Long userId, Long tenantId);

    void updateUserRole(Long userId, Long tenantId, String originRole, String targetRole);

    void removeUser(Long userId, Long tenantId);

    List<UserTenantDO> getTenantAdmins(Long tenantId);

    List<UserTenantDO> getTenantAllMembers(Long tenantId);

    List<UserTenantDO> getTenantMembers(Long tenantId);
}
