package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

public enum UsageCostErrorCodeEnum implements IErrorCode {
    USAGE_GRANULARITY_INVALID("A000950", "统计粒度不合法"),
    USAGE_TIME_RANGE_INVALID("A000951", "统计时间范围不合法"),
    USAGE_DIMENSION_INVALID("A000952", "分组维度不合法"),
    USAGE_FILTER_FORBIDDEN("A000953", "筛选资源无权访问"),
    USAGE_PAGINATION_INVALID("A000954", "分页参数不合法"),
    USAGE_SORT_INVALID("A000955", "排序参数不合法"),
    USAGE_QUERY_FAILED("B000950", "费用统计查询失败");

    private final String code;
    private final String message;

    UsageCostErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
