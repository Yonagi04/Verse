package com.yonagi.verse.dao.projection;

import lombok.Data;
import java.math.BigDecimal;
import java.math.BigInteger;

/** 维度用量聚合查询行。 */
@Data
public class UsageBreakdownRow {
    /** 分组业务 ID。 */ private Long dimensionId;
    /** 模型快照名，仅模型维度使用。 */ private String model;
    /** 输入 Token。 */ private BigInteger inputTokens;
    /** 输出 Token。 */ private BigInteger outputTokens;
    /** 总 Token。 */ private BigInteger totalTokens;
    /** 成功请求数。 */ private BigInteger requestCount;
    /** 可计算预估费用，分。 */ private BigDecimal estimatedCostFen;
    /** 精确用量数。 */ private BigInteger exactUsageCount;
    /** 预估用量数。 */ private BigInteger estimatedUsageCount;
    /** 未知用量数。 */ private BigInteger unknownUsageCount;
    /** 已计算费用数。 */ private BigInteger calculatedCount;
    /** 未启用计费数。 */ private BigInteger unpricedCount;
    /** 无法计算费用数。 */ private BigInteger uncalculableCount;
    /** 不可计费数。 */ private BigInteger notChargeableCount;
}
