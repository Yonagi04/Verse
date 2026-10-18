package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum UserModelActivityCalendarErrorCode implements IErrorCode {
    INVALID_RANGE("A001604", "活动范围仅支持最近365天或本年及前两年");
    private final String code;
    private final String message;
    public String code() { return code; }
    public String message() { return message; }
}
