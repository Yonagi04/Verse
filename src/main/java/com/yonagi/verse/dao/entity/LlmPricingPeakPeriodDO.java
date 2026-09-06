package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_llm_pricing_peak_period")
public class LlmPricingPeakPeriodDO {

    /** 自增主键。 */
    private Long id;

    /** 时段业务 ID。 */
    private Long periodId;

    /** 定价版本 ID。 */
    private Long pricingId;

    /** 星期位掩码。 */
    private Integer weekdayMask;

    /** 当日开始分钟数。 */
    private Integer startMinute;

    /** 当日结束分钟数。 */
    private Integer endMinute;

    /** 峰值缓存未命中输入 Token 单价。 */
    private BigDecimal peakCacheMissInputPriceFen;

    /** 峰值缓存命中输入 Token 单价。 */
    private BigDecimal peakCacheHitInputPriceFen;

    /** 峰值输出 Token 单价。 */
    private BigDecimal peakOutputPriceFen;

    /** 峰值单次请求价格。 */
    private BigDecimal peakRequestPriceFen;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
