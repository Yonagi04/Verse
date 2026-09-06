package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Token 用量费用快照实体。 */
@Data
@TableName("t_token_usage_cost")
public class TokenUsageCostDO {
    /** 自增主键。 */ private Long id;
    /** Token 用量事实主键。 */ private Long usageId;
    /** 定价版本业务 ID 快照。 */ private Long pricingId;
    /** 计费模式快照。 */ private String billingMode;
    /** 价格时段类型快照。 */ private String pricePeriodType;
    /** 价格时段业务 ID 快照。 */ private Long pricePeriodId;
    /** 定价生效时间快照。 */ private LocalDateTime priceEffectiveFrom;
    /** 定价失效时间快照。 */ private LocalDateTime priceEffectiveTo;
    /** 缓存未命中输入单价快照，分/百万 Token。 */ private BigDecimal cacheMissInputPriceFen;
    /** 缓存命中输入单价快照，分/百万 Token。 */ private BigDecimal cacheHitInputPriceFen;
    /** 输出单价快照，分/百万 Token。 */ private BigDecimal outputPriceFen;
    /** 单次请求价格快照，分。 */ private BigDecimal requestPriceFen;
    /** 预估费用，分。 */ private BigDecimal estimatedCostFen;
    /** 费用终态。 */ private String costStatus;
    /** 计费币种。 */ private String currency;
    /** 创建时间。 */ private LocalDateTime createTime;
}
