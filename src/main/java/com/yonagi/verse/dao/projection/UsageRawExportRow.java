package com.yonagi.verse.dao.projection;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.ExcelIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/** 逐请求用量导出行，不包含密钥和请求正文等敏感字段。 */
@Data
public class UsageRawExportRow {
    /** 用量事实主键，仅作为分页游标，不导出。 */ @ExcelIgnore private Long id;
    /** 请求开始时间。 */ @ExcelProperty("请求时间") private LocalDateTime requestStartedAt;
    /** 请求追踪 ID。 */ @ExcelProperty("请求ID") private String requestId;
    /** 成员显示名。 */ @ExcelProperty("成员") private String memberLabel;
    /** API Key 显示名。 */ @ExcelProperty("API Key") private String apiKeyLabel;
    /** 服务显示名。 */ @ExcelProperty("服务") private String serviceLabel;
    /** 实际模型名。 */ @ExcelProperty("模型") private String model;
    /** 请求状态。 */ @ExcelProperty("状态") private String status;
    /** 用量来源。 */ @ExcelProperty("用量来源") private String usageSource;
    /** 输入 Token，字符串避免表格精度丢失。 */ @ExcelProperty("输入Token") private String inputTokens;
    /** 输出 Token，字符串避免表格精度丢失。 */ @ExcelProperty("输出Token") private String outputTokens;
    /** 总 Token，字符串避免表格精度丢失。 */ @ExcelProperty("总Token") private String totalTokens;
    /** 费用状态。 */ @ExcelProperty("费用状态") private String costStatus;
    /** 预估费用，单位分，以字符串保持小数精度。 */ @ExcelProperty("预估费用(分)") private String estimatedCostFen;
    /** 币种。 */ @ExcelProperty("币种") private String currency;
}
