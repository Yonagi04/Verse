package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * Token 消耗记录实体
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Data
@TableName("t_token_usage")
public class TokenUsageDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 用户ID（业务ID）
     */
    private Long userId;

    /**
     * 租户ID（业务ID）
     */
    private Long tenantId;

    /**
     * API Key ID（业务ID）
     */
    private Long apiKeyId;

    /**
     * LLM服务ID（业务ID）
     */
    private Long serviceId;

    /**
     * 实际调用的模型名
     */
    private String model;

    /**
     * 输入Token数
     */
    private Integer promptTokens;

    /**
     * 输出Token数
     */
    private Integer completionTokens;

    /**
     * 总Token数
     */
    private Integer totalTokens;

    /**
     * 请求追踪ID
     */
    private String requestId;

    /**
     * 状态：SUCCESS / ABORTED / FAIL
     */
    private String status;

    /**
     * usage来源：EXACT / ESTIMATED / UNKNOWN
     */
    private String usageSource;

    /**
     * 创建时间
     */
    private Date createTime;

    /** 事件唯一 ID，用于幂等落库。 */
    private String eventId;

    /** 请求进入网关的时间。 */
    private LocalDateTime requestStartedAt;

    /** 标准化输入 Token 数。 */
    private Long inputTokens;

    /** 标准化缓存命中输入 Token 数。 */
    private Long cachedInputTokens;

    /** 标准化缓存写入输入 Token 数。 */
    private Long cacheWriteInputTokens;

    /** 标准化输出 Token 数。 */
    private Long outputTokens;

    /** 标准化总 Token 数。 */
    private Long normalizedTotalTokens;

    /** 命中的用量解析器标识。 */
    private String usageParser;

    /** 命中的定价版本 ID。 */
    private Long pricingId;

    /** 计费模式。 */
    private String billingMode;

    /** 命中的价格时段类型。 */
    private String pricePeriodType;

    /** 命中的峰值时段 ID。 */
    private Long pricePeriodId;

    /** 定价版本生效时间。 */
    private LocalDateTime priceEffectiveFrom;

    /** 定价版本失效时间。 */
    private LocalDateTime priceEffectiveTo;

    /** 缓存未命中输入 Token 单价，单位为分/百万 Token。 */
    private BigDecimal cacheMissInputPriceFen;

    /** 缓存命中输入 Token 单价，单位为分/百万 Token。 */
    private BigDecimal cacheHitInputPriceFen;

    /** 输出 Token 单价，单位为分/百万 Token。 */
    private BigDecimal outputPriceFen;

    /** 单次请求价格，单位为分。 */
    private BigDecimal requestPriceFen;

    /** 预估费用，单位为分。 */
    private BigDecimal estimatedCostFen;

    /** 费用计算状态。 */
    private String costStatus;

    /** 计费币种。 */
    private String currency;

    /** 原始用量详情 JSON，不含请求或响应正文。 */
    private String usageDetailsJson;
}
