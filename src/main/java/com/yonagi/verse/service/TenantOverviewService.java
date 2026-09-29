package com.yonagi.verse.service;

import com.yonagi.verse.dto.resp.TenantOverviewRespDTO;

/** 已加入租户的跨当前上下文只读摘要。 */
public interface TenantOverviewService {
    /** 批量读取本人全部有效租户的授权摘要。 */
    TenantOverviewRespDTO.Batch batch(Long userId);

    /** 重新鉴权后读取一个目标租户的授权摘要和脱敏动态。 */
    TenantOverviewRespDTO.Detail detail(Long userId, Long tenantId);
}
