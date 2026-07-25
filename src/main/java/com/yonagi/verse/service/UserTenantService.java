package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.UserTenantDO;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/25 10:15
 */
public interface UserTenantService extends IService<UserTenantDO> {

    Boolean createUserTenant(Long userId, Long tenantId);

    String getRoleByUserIdAndTenantId(Long userId, Long tenantId);
}
