package com.yonagi.verse.service.pricing;

import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.service.usage.UsageBreakdown;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class CostCalculator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final int MAX_COST_INTEGER_DIGITS = 20;
    private static final int MAX_COST_SCALE = 18;

    public CostResult calculate(String terminalStatus, UsageBreakdown usage, PricingSnapshot pricing) {
        if (!"SUCCESS".equals(terminalStatus)) {
            return CostResult.of(CostStatus.NOT_CHARGEABLE);
        }
        if (pricing == null || !pricing.priced()) {
            return CostResult.of(CostStatus.UNPRICED);
        }
        if (pricing.billingMode() == BillingMode.REQUEST) {
            return calculated(pricing.requestPriceFen());
        }
        if (usage == null || !usage.valid() || usage.inputTokens() == null
                || usage.cachedInputTokens() == null || usage.outputTokens() == null
                || pricing.cacheMissInputPriceFen() == null || pricing.cacheHitInputPriceFen() == null
                || pricing.outputPriceFen() == null) {
            return CostResult.of(CostStatus.UNCALCULABLE);
        }
        long misses = usage.inputTokens() - usage.cachedInputTokens();
        if (misses < 0) {
            return CostResult.of(CostStatus.UNCALCULABLE);
        }
        BigDecimal cost = BigDecimal.valueOf(misses).multiply(pricing.cacheMissInputPriceFen())
                .add(BigDecimal.valueOf(usage.cachedInputTokens()).multiply(pricing.cacheHitInputPriceFen()))
                .add(BigDecimal.valueOf(usage.outputTokens()).multiply(pricing.outputPriceFen()))
                .divide(ONE_MILLION);
        return calculated(cost);
    }

    private CostResult calculated(BigDecimal cost) {
        if (cost == null) {
            return CostResult.of(CostStatus.UNCALCULABLE);
        }
        BigDecimal normalized = cost.stripTrailingZeros();
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        if (normalized.scale() > MAX_COST_SCALE || integerDigits > MAX_COST_INTEGER_DIGITS) {
            return CostResult.of(CostStatus.UNCALCULABLE);
        }
        return new CostResult(CostStatus.CALCULATED, cost);
    }
}
