package com.yonagi.verse.service.pricing;

import com.yonagi.verse.common.enums.CostStatus;

import java.math.BigDecimal;

public record CostResult(CostStatus status, BigDecimal estimatedCostFen) {
    public static CostResult of(CostStatus status) {
        return new CostResult(status, null);
    }
}
