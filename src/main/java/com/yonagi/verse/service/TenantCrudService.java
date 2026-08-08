package com.yonagi.verse.service;

import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;

import java.util.List;

/**
 * 租户生命周期管理服务接口。
 */
public interface TenantCrudService {

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
