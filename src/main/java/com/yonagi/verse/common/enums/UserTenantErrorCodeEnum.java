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
    USER_TENANT_RELATION_NOT_EXIST("A000401", "用户租户关系不存在"),
    USER_TENANT_ROLE_UPDATE_FAILED("A000402", "用户租户角色更新失败"),
    USER_REMOVE_FAILED("A000403", "用户移除失败"),

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
