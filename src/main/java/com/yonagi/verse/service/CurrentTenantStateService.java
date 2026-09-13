package com.yonagi.verse.service;

import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.projection.CurrentTenantState;

/**
 * 当前租户权威状态服务。
 */
public interface CurrentTenantStateService {

    /** 解析并在必要时修复用户的当前租户。 */
    CurrentTenantState resolveCurrentTenant(Long userId);

    /** 在固定锁顺序下切换到有效目标租户。 */
    CurrentTenantState switchTenant(Long userId, Long tenantId);

    /** 结束成员关系，并仅在目标仍为当前租户时回退。 */
    Long removeMembershipAndFallback(Long userId, Long tenantId);

    /** 停用团队租户并集合式回退所有仍受影响的用户。 */
    TenantDO closeTenantAndFallback(Long operatorUserId, Long tenantId);
}
