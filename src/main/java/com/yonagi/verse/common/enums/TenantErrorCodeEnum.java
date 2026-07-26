package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * 租户相关错误码
 *
 * @author Yonagi
 */
public enum TenantErrorCodeEnum implements IErrorCode {

    TENANT_CREATE_ERROR("A000300", "租户创建失败"),
    TENANT_INVITE_CODE_CREATE_ERROR("A000301", "租户邀请码创建失败"),
    TENANT_JOIN_ERROR("A000302", "加入租户失败"),

    TENANT_NOT_EXIST("B000300", "租户不存在"),
    TENANT_ID_IS_NULL("B000301", "租户ID不能为空"),
    TENANT_PERMISSION_DENIED("B000302", "无租户操作权限"),
    TENANT_COUNT_EXCEEDS("B000303", "用户最多只能加入/创建10个租户"),
    TENANT_INVITE_CODE_EXPIRED("B000304", "租户邀请码过期"),
    TENANT_HAS_BEEN_JOINED("B000305", "已加入此租户"),
    TENANT_JOIN_PROHIBITED("B000306", "此租户不能加入"),
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
