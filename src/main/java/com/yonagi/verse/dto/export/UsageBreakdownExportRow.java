package com.yonagi.verse.dto.export;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/** 维度排行榜导出行。 */
@Data
public class UsageBreakdownExportRow {
    /** 维度项 ID。 */ @ExcelProperty("ID") private String id;
    /** 维度项显示名。 */ @ExcelProperty("名称") private String label;
    /** 当前排序指标占比。 */ @ExcelProperty("占比") private String ratio;
    /** 输入 Token。 */ @ExcelProperty("输入Token") private String inputTokens;
    /** 输出 Token。 */ @ExcelProperty("输出Token") private String outputTokens;
    /** 总 Token。 */ @ExcelProperty("总Token") private String totalTokens;
    /** 请求数。 */ @ExcelProperty("请求数") private String requestCount;
    /** 预估费用，单位分。 */ @ExcelProperty("预估费用(分)") private String estimatedCostFen;
    /** 无法计费请求数。 */ @ExcelProperty("无法计费数") private String uncalculableCount;
    /** 未定价请求数。 */ @ExcelProperty("未定价数") private String unpricedCount;
}
