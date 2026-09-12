package com.yonagi.verse.common.enums;

/** 用量对比排序指标。 */
public enum UsageBreakdownOrder {
    TOTAL_TOKENS, ESTIMATED_COST_FEN, REQUEST_COUNT;
    public static UsageBreakdownOrder parse(String value) {
        if (value == null || value.isBlank()) return TOTAL_TOKENS;
        String normalized=value.replaceAll("([a-z])([A-Z])","$1_$2").replace('-','_').toUpperCase();
        try { return valueOf(normalized); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("用量排序指标不合法"); }
    }
}
