package com.yonagi.verse.service.pricing;

import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.service.usage.UsageBreakdown;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CostCalculatorTest {

    @Test
    void preservesFractionalFenForCachedAndUncachedTokens() {
        UsageBreakdown usage = new UsageBreakdown(100L, 40L, 0L, 20L, 120L, "EXACT", "fixture", null, true);
        PricingSnapshot pricing = new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", PricePeriodType.BASE, null,
                new BigDecimal("100"), new BigDecimal("25"), new BigDecimal("200"), null, null, null);
        CostResult result = new CostCalculator().calculate("SUCCESS", usage, pricing);
        assertEquals(CostStatus.CALCULATED, result.status());
        assertEquals(0, result.estimatedCostFen().compareTo(new BigDecimal("0.011")));
    }

    @Test
    void missingUsageIsExplicitlyUncalculable() {
        CostResult result = new CostCalculator().calculate("SUCCESS", UsageBreakdown.unavailable("fixture", null),
                new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", PricePeriodType.BASE, null,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null, null));
        assertEquals(CostStatus.UNCALCULABLE, result.status());
        assertNull(result.estimatedCostFen());
    }

    @Test
    void databaseDecimalOverflowIsExplicitlyUncalculable() {
        UsageBreakdown usage = new UsageBreakdown(
                Long.MAX_VALUE, 0L, 0L, Long.MAX_VALUE, null, "EXACT", "fixture", null, true
        );
        BigDecimal maximumPrice = new BigDecimal("999999999999999999.999999999999");
        PricingSnapshot pricing = new PricingSnapshot(
                1L, BillingMode.TOKEN, "CNY", PricePeriodType.BASE, null,
                maximumPrice, BigDecimal.ZERO, maximumPrice, null, null, null
        );

        CostResult result = new CostCalculator().calculate("SUCCESS", usage, pricing);

        assertEquals(CostStatus.UNCALCULABLE, result.status());
        assertNull(result.estimatedCostFen());
    }
}
