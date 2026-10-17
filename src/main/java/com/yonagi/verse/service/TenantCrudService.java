package com.yonagi.verse.service;

import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;

import java.util.List;

/**
 * 租户生命周期管理服务接口。
 */
public interface TenantCrudService {
    /** 注销事务先锁全部可能清理的自有租户，再锁用户，阻止并发加入及反向获取租户锁。 */
    List<Long> lockOwnedTenantsForAccountClosure(Long userId);

    /** 用户行锁后只复核候选，出现未锁定租户时拒绝重试，禁止补锁。 */
    void validateAccountClosureTenantLocks(Long userId, List<Long> lockedTenantIds);

    /** 同步注销只清理已提前锁定的租户；异步补偿使用独立的幂等清理入口。 */
    void deleteClosedUsersLockedTenants(Long userId, List<Long> lockedTenantIds);

    /** 内部注销流程：停用并逻辑删除个人租户及没有其他未退出成员的自有团队。 */
    void deleteClosedUsersPersonalAndSoleMemberTenants(Long userId);

    List<TenantInfoListRespDTO> listTenants(Long userId);

    Boolean createTenant(Long userId, TenantCreateReqDTO requestParam);

    Long createPersonalTenant(Long userId, String tenantName);

    Boolean updateTenant(Long userId, Long tenantId, TenantUpdateReqDTO requestParam);

    TenantInfoRespDTO getTenantInfo(Long userId, Long tenantId);

    Long getPersonalTenantId(Long userId);

    TenantClosePrepareRespDTO prepareCloseTenant(Long userId, Long tenantId);

    Boolean closeTenant(Long userId, Long tenantId, TenantCloseReqDTO requestParam);

    TenantSwitchRespDTO switchTenant(Long userId, Long tenantId);

    Boolean sendNotificationInTenant(Long userId, Long tenantId, TenantSendNotificationReqDTO requestParam);
}
