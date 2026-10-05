package com.yonagi.verse.service;

import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;

import java.util.List;

/**
 * 租户生命周期管理服务接口。
 */
public interface TenantCrudService {
    /** 注销事务先锁自有启用团队，再锁用户，阻止最终校验后并发加入。 */
    List<Long> lockOwnedTeamTenantsForAccountClosure(Long userId);

    /** 用户行锁后确认没有新增的自有启用团队，避免遗漏并发创建或交接。 */
    void validateAccountClosureTenantLocks(Long userId, List<Long> lockedTeamTenantIds);

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
