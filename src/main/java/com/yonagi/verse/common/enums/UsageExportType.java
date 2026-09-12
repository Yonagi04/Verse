package com.yonagi.verse.common.enums;

import java.util.Locale;

/** 用量报表导出类型。 */
public enum UsageExportType {
    /** 时间序列汇总。 */ TIMESERIES,
    /** 维度排行榜。 */ BREAKDOWN,
    /** 逐请求明细。 */ RAW;

    /** 解析 HTTP 参数。 */
    public static UsageExportType parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
