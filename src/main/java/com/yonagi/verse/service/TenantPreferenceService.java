package com.yonagi.verse.service;

import com.yonagi.verse.dto.req.TenantPreferenceReqDTO;
import com.yonagi.verse.dto.resp.TenantPreferenceRespDTO;

/** 当前用户的跨设备租户偏好。 */
public interface TenantPreferenceService {
    /** 更新仍有效的目标租户偏好。 */
    TenantPreferenceRespDTO update(Long userId, Long tenantId, TenantPreferenceReqDTO request);
}
