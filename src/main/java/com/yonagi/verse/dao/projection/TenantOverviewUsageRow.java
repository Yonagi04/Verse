package com.yonagi.verse.dao.projection;

import lombok.Data;
import java.time.LocalDateTime;

/** 近三十天小时投影上卷结果。 */
@Data
public class TenantOverviewUsageRow {
    /** 租户业务 ID。 */ private Long tenantId;
    /** 成功请求的 Token 总量。 */ private Long totalTokens;
    /** 成功请求数。 */ private Long requestCount;
    /** 精确用量请求数。 */ private Long exactUsageCount;
    /** 预估用量请求数。 */ private Long estimatedUsageCount;
    /** 未知用量请求数。 */ private Long unknownUsageCount;
    /** 所含小时桶的最近投影更新时间。 */ private LocalDateTime maxUpdatedAt;
}
