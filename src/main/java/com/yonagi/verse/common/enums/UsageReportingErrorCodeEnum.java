package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/** 用量报表错误码。 */
public enum UsageReportingErrorCodeEnum implements IErrorCode {
    GRANULARITY_INVALID("A000950", "统计粒度不合法"),
    RANGE_INVALID("A000951", "统计时间范围不合法"),
    PERMISSION_DENIED("A000952", "无权查看该范围的用量数据"),
    FILTER_INVALID("A000953", "用量筛选条件不合法"),
    DIMENSION_INVALID("A000954", "用量对比维度不合法"),
    EXPORT_INVALID("A000955", "导出参数不合法"),
    EXPORT_TOO_LARGE("A000956", "导出数据量超过限制"),
    EXPORT_BUSY("A000957", "导出任务繁忙，请稍后重试"),
    EXPORT_FAILED("B000950", "用量报表导出失败");
    private final String code; private final String message;
    UsageReportingErrorCodeEnum(String code, String message) { this.code=code; this.message=message; }
    @Override public String code() { return code; }
    @Override public String message() { return message; }
}
