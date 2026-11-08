package com.yonagi.verse.service.pricing;

import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.service.usage.UsageBreakdown;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CostCalculatorTest {
    private final CostCalculator calculator = new CostCalculator();

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

    @ParameterizedTest
    @MethodSource("incompleteTokenEvidence")
    void incompleteTokenEvidenceDoesNotBecomeZeroCost(UsageBreakdown usage, PricingSnapshot pricing) {
        CostResult result = calculator.calculate("SUCCESS", usage, pricing);

        assertEquals(CostStatus.UNCALCULABLE, result.status());
        assertNull(result.estimatedCostFen());
    }

    private static Stream<Arguments> incompleteTokenEvidence() {
        BigDecimal rate = BigDecimal.ONE;
        PricingSnapshot priced = tokenPricing(rate, rate, rate);
        UsageBreakdown complete = usage(10L, 5L, 2L);
        return Stream.of(
                Arguments.of(null, priced),
                Arguments.of(usage(null, 5L, 2L), priced),
                Arguments.of(usage(10L, null, 2L), priced),
                Arguments.of(usage(10L, 5L, null), priced),
                Arguments.of(usage(10L, 11L, 2L), priced),
                Arguments.of(complete, tokenPricing(null, rate, rate)),
                Arguments.of(complete, tokenPricing(rate, null, rate)),
                Arguments.of(complete, tokenPricing(rate, rate, null)));
    }

    @ParameterizedTest
    @CsvSource({
            "SUCCESS,0,CALCULATED",
            "SUCCESS,0.000000000000000001,CALCULATED",
            "SUCCESS,0.0000000000000000001,UNCALCULABLE",
            "SUCCESS,,UNCALCULABLE",
            "FAIL,1,NOT_CHARGEABLE"
    })
    void requestPricingUsesTheSnapshotAndEnforcesAmountPrecision(String status, BigDecimal amount,
                                                                 CostStatus expected) {
        PricingSnapshot pricing = new PricingSnapshot(1L, BillingMode.REQUEST, "CNY", PricePeriodType.BASE,
                null, null, null, null, amount, null, null);

        CostResult result = calculator.calculate(status, null, pricing);

        assertEquals(expected, result.status());
        assertEquals(expected == CostStatus.CALCULATED ? amount : null, result.estimatedCostFen());
    }

    @Test
    void unavailablePriceIsDistinctFromIncompleteUsage() {
        CostResult result = calculator.calculate("SUCCESS", usage(10L, 5L, 2L), PricingSnapshot.unpriced());

        assertEquals(CostStatus.UNPRICED, result.status());
        assertNull(result.estimatedCostFen());
    }

    private static PricingSnapshot tokenPricing(BigDecimal miss, BigDecimal hit, BigDecimal output) {
        return new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", PricePeriodType.BASE,
                null, miss, hit, output, null, null, null);
    }

    private static UsageBreakdown usage(Long input, Long cached, Long output) {
        return new UsageBreakdown(input, cached, 0L, output, null, "EXACT", "fixture", null, true);
    }
}
