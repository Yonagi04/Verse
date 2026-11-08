package com.yonagi.verse.common.util;

import java.math.BigDecimal;
import java.math.BigInteger;

/** JSON 数字的精确整数判断；业务上界和错误契约由调用方维护。 */
public final class JsonNumbers {
    private JsonNumbers() {
    }

    /**
     * 判断标准 JSON 数字表示是否为不超过上界的精确正整数；不转换字符串或未知 Number 子类。
     *
     * @param value 保留解析精度的数字，浮点值仅能校验其当前十进制表示
     * @param maximum 包含端点的业务上界
     * @return 类型、整数性及范围均合法时为 true
     */
    public static boolean isPositiveInteger(Object value, long maximum) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long integer = ((Number) value).longValue();
            return integer > 0 && integer <= maximum;
        }
        BigDecimal decimal;
        if (value instanceof BigDecimal exactDecimal) {
            decimal = exactDecimal;
        } else if (value instanceof BigInteger exactInteger) {
            decimal = new BigDecimal(exactInteger);
        } else if (value instanceof Double || value instanceof Float) {
            if (!Double.isFinite(((Number) value).doubleValue())) {
                return false;
            }
            decimal = new BigDecimal(value.toString());
        } else {
            return false;
        }
        return decimal.signum() > 0 && decimal.compareTo(BigDecimal.valueOf(maximum)) <= 0
                && decimal.stripTrailingZeros().scale() <= 0;
    }
}
