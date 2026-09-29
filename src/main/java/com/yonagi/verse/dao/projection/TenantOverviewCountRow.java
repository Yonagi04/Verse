package com.yonagi.verse.dao.projection;

import lombok.Data;

/** 租户分组计数投影。 */
@Data
public class TenantOverviewCountRow {
    /** 租户业务 ID。 */ private Long tenantId;
    /** 对应维度的记录数。 */ private Long total;
}
