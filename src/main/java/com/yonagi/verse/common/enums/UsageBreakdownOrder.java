package com.yonagi.verse.common.enums;

import java.util.Locale;
import java.util.regex.Pattern;

/** 用量对比排序指标。 */
public enum UsageBreakdownOrder {
    /** 总 Token 数。 */
    TOTAL_TOKENS,
    /** 可计算的预估费用，单位为分。 */
    ESTIMATED_COST_FEN,
    /** 成功请求数。 */
    REQUEST_COUNT;

    private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("([a-z])([A-Z])");

    /** 接受公开排序字段的 camelCase、kebab-case 和枚举写法，空白按总 Token 数排序。 */
    public static UsageBreakdownOrder parse(String value) {
        if (value == null || value.isBlank()) {
            return TOTAL_TOKENS;
        }
        String normalized = CAMEL_CASE_BOUNDARY.matcher(value).replaceAll("$1_$2")
                .replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("用量排序指标不合法");
        }
    }
}
