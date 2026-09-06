package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PricingConfigRespDTO {

    /** 是否启用计费。 */
    private Boolean enabled;

    /** 计费模式。 */
    private String billingMode;

    /** 计费币种。 */
    private String currency;

    /** 当前定价版本 ID。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long pricingId;

    /** Token 基础时段价格。 */
    private TokenPrices baseTokenPrices;

    /** 单次请求基础价格，单位为分。 */
    private BigDecimal baseRequestPriceFen;

    /** 峰值时段配置。 */
    private List<PeakPeriod> peakPeriods;

    @Data
    public static class TokenPrices {

        /** 缓存未命中输入 Token 单价。 */
        private BigDecimal cacheMissInputPriceFen;

        /** 缓存命中输入 Token 单价。 */
        private BigDecimal cacheHitInputPriceFen;

        /** 输出 Token 单价。 */
        private BigDecimal outputPriceFen;
    }

    @Data
    public static class PeakPeriod {

        /** 时段 ID。 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long periodId;

        /** 生效星期列表。 */
        private List<Integer> weekdays;

        /** 开始时间。 */
        private String startTime;

        /** 结束时间。 */
        private String endTime;

        /** Token 峰值时段价格。 */
        private TokenPrices tokenPrices;

        /** 单次请求峰值时段价格。 */
        private BigDecimal requestPriceFen;
    }
}
