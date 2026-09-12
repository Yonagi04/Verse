package com.yonagi.verse.dto.export;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/** 时间序列报表导出行。 */
@Data
public class UsageTimeseriesExportRow {
    /** 时间桶。 */ @ExcelProperty("时间") private String bucket;
    /** 输入 Token。 */ @ExcelProperty("输入Token") private String inputTokens;
    /** 输出 Token。 */ @ExcelProperty("输出Token") private String outputTokens;
    /** 总 Token。 */ @ExcelProperty("总Token") private String totalTokens;
    /** 请求数。 */ @ExcelProperty("请求数") private String requestCount;
    /** 预估费用，单位分。 */ @ExcelProperty("预估费用(分)") private String estimatedCostFen;
    /** 精确用量请求数。 */ @ExcelProperty("精确用量数") private String exactUsageCount;
    /** 预估用量请求数。 */ @ExcelProperty("预估用量数") private String estimatedUsageCount;
    /** 未知用量请求数。 */ @ExcelProperty("未知用量数") private String unknownUsageCount;
}
