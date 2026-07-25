package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/25 10:48
 */
public enum UserTenantErrorCodeEnum implements IErrorCode {

    USER_TENANT_CREATE_FAILED("A000400", "用户租户联系创建失败"),

    USER_ID_IS_NULL("B000400", "用户ID不能为空"),
    TENANT_ID_IS_NULL("B000401", "租户ID不能为空");

    private final String code;
    private final String message;

    UserTenantErrorCodeEnum(String code, String message) {
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
