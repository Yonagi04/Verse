package com.yonagi.verse.dto.req;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 不可变定价版本的完整替换配置。
 */
@Data
public class PricingConfigReqDTO {

    /** 是否启用计费。 */
    private Boolean enabled;

    /** 计费模式：TOKEN 或 REQUEST。 */
    private String billingMode;

    /** Token 基础时段价格。 */
    private TokenPricesReqDTO baseTokenPrices;

    /** 单次请求基础价格，单位为分。 */
    private BigDecimal baseRequestPriceFen;

    /** 峰值时段配置。 */
    private List<PeakPeriodReqDTO> peakPeriods;

    @Data
    public static class TokenPricesReqDTO {

        /** 每百万缓存未命中输入 Token 价格，单位为分。 */
        private BigDecimal cacheMissInputPriceFen;

        /** 每百万缓存命中输入 Token 价格，单位为分，可为零。 */
        private BigDecimal cacheHitInputPriceFen;

        /** 每百万输出 Token 价格，单位为分。 */
        private BigDecimal outputPriceFen;
    }

    @Data
    public static class PeakPeriodReqDTO {

        /** 生效星期列表，1 表示星期一，7 表示星期日。 */
        private List<Integer> weekdays;

        /** 开始时间，格式为 HH:mm。 */
        private String startTime;

        /** 结束时间，格式为 HH:mm，允许 24:00。 */
        private String endTime;

        /** Token 峰值时段价格。 */
        private TokenPricesReqDTO tokenPrices;

        /** 单次请求峰值时段价格，单位为分。 */
        private BigDecimal requestPriceFen;
    }
}
