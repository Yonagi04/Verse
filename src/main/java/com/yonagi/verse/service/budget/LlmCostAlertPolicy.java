package com.yonagi.verse.service.budget;

import java.math.BigDecimal;

/** 精确原金额比较，跳到达限时不补发已过时预警。 */
public final class LlmCostAlertPolicy {
    private LlmCostAlertPolicy() { }
    public static String event(boolean enabled, BigDecimal limit, BigDecimal used,
            boolean warning, int percent) {
        if (!enabled || limit == null) return null;
        if (used.compareTo(limit) >= 0) return "LIMIT";
        if (warning && used.multiply(BigDecimal.valueOf(100))
                .compareTo(limit.multiply(BigDecimal.valueOf(percent))) >= 0) return "WARNING";
        return null;
    }
}
