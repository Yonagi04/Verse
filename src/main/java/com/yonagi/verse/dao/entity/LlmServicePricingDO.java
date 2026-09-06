package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_llm_service_pricing")
public class LlmServicePricingDO {

    /** 自增主键。 */
    private Long id;

    /** 定价版本业务 ID。 */
    private Long pricingId;

    /** 租户 ID。 */
    private Long tenantId;

    /** 服务 ID。 */
    private Long serviceId;

    /** 计费模式。 */
    private String billingMode;

    /** 计费币种。 */
    private String currency;

    /** 基础缓存未命中输入 Token 单价。 */
    private BigDecimal baseCacheMissInputPriceFen;

    /** 基础缓存命中输入 Token 单价。 */
    private BigDecimal baseCacheHitInputPriceFen;

    /** 基础输出 Token 单价。 */
    private BigDecimal baseOutputPriceFen;

    /** 基础单次请求价格。 */
    private BigDecimal baseRequestPriceFen;

    /** 生效开始时间。 */
    private LocalDateTime effectiveFrom;

    /** 生效结束时间，为空表示当前有效。 */
    private LocalDateTime effectiveTo;

    /** 创建人用户 ID。 */
    private Long createdBy;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
