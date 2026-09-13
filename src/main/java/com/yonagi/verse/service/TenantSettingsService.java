package com.yonagi.verse.service;

import com.yonagi.verse.dto.req.TenantSettingsUpdateReqDTO;
import com.yonagi.verse.dto.resp.TenantSettingsRespDTO;

/**
 * 租户自定义设置领域服务。
 *
 * @author Yonagi
 */
public interface TenantSettingsService {

    /** 读取目标租户设置，所有有效成员均可访问。 */
    TenantSettingsRespDTO getSettings(Long userId, Long tenantId);

    /** 完整更新目标租户设置，仅管理员可访问。 */
    TenantSettingsRespDTO updateSettings(Long userId, Long tenantId, TenantSettingsUpdateReqDTO requestParam);
}
