package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * API Key 相关错误码
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/08/16
 */
public enum ApiKeyErrorCodeEnum implements IErrorCode {

    API_KEY_EXPIRE_DATE_IS_INVALID("A000600", "Api Key过期时间早于当前日期"),
    API_KEY_ID_IS_NULL("A000601", "API Key ID不能为空"),

    API_KEY_CREATE_FAILED("B000600", "API Key 创建失败"),
    API_KEY_NOT_EXIST("B000601", "API Key 不存在"),
    API_KEY_REVOKE_FAILED("B000602", "API Key 吊销失败"),
    ;

    private final String code;
    private final String message;

    ApiKeyErrorCodeEnum(String code, String message) {
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
