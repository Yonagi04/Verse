package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * 租户相关错误码
 *
 * @author Yonagi
 */
public enum TenantErrorCodeEnum implements IErrorCode {

    TENANT_NOT_EXIST("B000300", "租户不存在"),
    TENANT_PERMISSION_DENIED("B000301", "无租户操作权限"),
    TENANT_NOT_JOINED("B000308", "用户未加入该租户");

    private final String code;
    private final String message;

    TenantErrorCodeEnum(String code, String message) {
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
