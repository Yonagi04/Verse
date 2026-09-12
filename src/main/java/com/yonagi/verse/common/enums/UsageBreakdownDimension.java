package com.yonagi.verse.common.enums;

/** 用量对比维度。 */
public enum UsageBreakdownDimension {
    MODEL, API_KEY, MEMBER;
    public static UsageBreakdownDimension parse(String value) {
        if (value == null || value.isBlank()) return MODEL;
        try { return valueOf(value.trim().replace('-', '_').toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("用量对比维度不合法"); }
    }
}
