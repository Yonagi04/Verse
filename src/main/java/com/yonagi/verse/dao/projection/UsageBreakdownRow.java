package com.yonagi.verse.dao.projection;

import lombok.Data;
import java.math.BigDecimal;

/** 维度用量聚合查询行。 */
@Data
public class UsageBreakdownRow {
    /** 分组业务 ID。 */ private Long dimensionId;
    /** 模型快照名，仅模型维度使用。 */ private String model;
    /** 输入 Token。 */ private Long inputTokens;
    /** 输出 Token。 */ private Long outputTokens;
    /** 总 Token。 */ private Long totalTokens;
    /** 成功请求数。 */ private Long requestCount;
    /** 可计算预估费用，分。 */ private BigDecimal estimatedCostFen;
    /** 精确用量数。 */ private Long exactUsageCount;
    /** 预估用量数。 */ private Long estimatedUsageCount;
    /** 未知用量数。 */ private Long unknownUsageCount;
    /** 已计算费用数。 */ private Long calculatedCount;
    /** 未启用计费数。 */ private Long unpricedCount;
    /** 无法计算费用数。 */ private Long uncalculableCount;
    /** 不可计费数。 */ private Long notChargeableCount;
}
