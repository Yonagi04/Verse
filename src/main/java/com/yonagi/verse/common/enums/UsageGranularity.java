package com.yonagi.verse.common.enums;

/** 用量报表时间粒度。 */
public enum UsageGranularity {
    HOUR, DAY, WEEK, MONTH;

    /** 解析忽略大小写的接口值。 */
    public static UsageGranularity parse(String value) {
        if (value == null || value.isBlank()) return DAY;
        try { return valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("不支持的用量统计粒度"); }
    }
}
