package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/** 用量报表错误码。 */
public enum UsageReportingErrorCodeEnum implements IErrorCode {
    GRANULARITY_INVALID("A000950", "统计粒度不合法"),
    RANGE_INVALID("A000951", "统计时间范围不合法"),
    PERMISSION_DENIED("A000952", "无权查看该范围的用量数据");
    private final String code; private final String message;
    UsageReportingErrorCodeEnum(String code, String message) { this.code=code; this.message=message; }
    @Override public String code() { return code; }
    @Override public String message() { return message; }
}
