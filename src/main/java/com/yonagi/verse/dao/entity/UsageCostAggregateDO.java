package com.yonagi.verse.dao.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用量费用聚合查询结果。
 *
 * @author Yonagi
 */
@Data
public class UsageCostAggregateDO {

    /**
     * 分组维度业务 ID。
     */
    private Long dimensionId;

    /**
     * 时间桶起始时间。
     */
    private LocalDateTime bucketStart;

    /**
     * 请求数。
     */
    private Long requestCount;

    /**
     * 输入 Token 数。
     */
    private Long inputTokens;

    /**
     * 缓存命中输入 Token 数。
     */
    private Long cachedInputTokens;

    /**
     * 输出 Token 数。
     */
    private Long outputTokens;

    /**
     * 总 Token 数。
     */
    private Long totalTokens;

    /**
     * 预估费用，单位为分。
     */
    private BigDecimal estimatedCostFen;

    /**
     * 无法计算费用的请求数。
     */
    private Long uncalculableRequestCount;
}
