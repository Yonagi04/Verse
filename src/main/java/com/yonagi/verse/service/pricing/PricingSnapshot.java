package com.yonagi.verse.service.pricing;

import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.PricePeriodType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 终态用量事件携带的不可变定价快照。
 */
public record PricingSnapshot(
        Long pricingId,
        BillingMode billingMode,
        String currency,
        PricePeriodType periodType,
        Long periodId,
        BigDecimal cacheMissInputPriceFen,
        BigDecimal cacheHitInputPriceFen,
        BigDecimal outputPriceFen,
        BigDecimal requestPriceFen,
        Instant effectiveFrom,
        Instant effectiveTo) {
    public static PricingSnapshot unpriced() {
        return new PricingSnapshot(null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean priced() {
        return pricingId != null;
    }
}
