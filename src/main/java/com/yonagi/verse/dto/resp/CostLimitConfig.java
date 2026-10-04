package com.yonagi.verse.dto.resp;

import com.yonagi.verse.dao.entity.ApiKeyDO;
import java.math.BigDecimal;

/** 正式配置；全部金额为整数分字符串。 */
public record CostLimitConfig(
        /** 开关。 */ boolean enabled,
        /** 日限额。 */ String dailyLimitFen,
        /** 周限额。 */ String weeklyLimitFen,
        /** 月限额。 */ String monthlyLimitFen,
        /** 配置版本。 */ String version) {
    public static CostLimitConfig from(ApiKeyDO key) {
        return new CostLimitConfig(Boolean.TRUE.equals(key.getCostLimitEnabled()), amount(key.getCostLimitDailyFen()),
                amount(key.getCostLimitWeeklyFen()), amount(key.getCostLimitMonthlyFen()),
                String.valueOf(key.getCostConfigVersion() == null ? 0 : key.getCostConfigVersion()));
    }
    private static String amount(BigDecimal value) { return value == null ? null : value.toPlainString(); }
}
