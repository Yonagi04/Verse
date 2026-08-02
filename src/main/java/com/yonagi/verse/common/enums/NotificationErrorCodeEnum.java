package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 21:32
 */
public enum NotificationErrorCodeEnum implements IErrorCode {

    NOTIFICATION_NOT_FOUND("A000500", "通知不存在"),
    NOTIFICATION_READ_FAILED("A000501", "通知标记为已读失败")
    ;

    private final String code;
    private final String message;

    NotificationErrorCodeEnum(String code, String message) {
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
