package com.yonagi.verse.service;

import com.yonagi.verse.dto.resp.TenantActivityListRespDTO;
import com.yonagi.verse.dto.resp.TenantActivityStatusRespDTO;

/** 租户动态只读查询服务。 */
public interface TenantActivityQueryService {

    /** 查询目标租户的动态记录开关状态。 */
    TenantActivityStatusRespDTO getStatus(Long userId, Long tenantId);

    /** 按稳定游标查询目标租户动态。 */
    TenantActivityListRespDTO listActivities(Long userId, Long tenantId, Integer limit, String cursor);
}
