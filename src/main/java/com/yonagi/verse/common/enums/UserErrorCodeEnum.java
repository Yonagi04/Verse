package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/06/18 14:55
 */
public enum UserErrorCodeEnum implements IErrorCode {

    USER_NOT_EXIST("B000200", "用户记录不存在"),
    USER_EXIST("B000201", "用户记录已存在"),
    USERNAME_EXIST("B000202", "用户名已存在"),
    EMAIL_BIND_COUNT_EXCEED("B000203", "邮箱绑定数量超过限制，单个邮箱最多只可绑定3个用户"),
    USER_SAVED_ERROR("B000204", "用户记录保存失败"),
    USER_STATUS_DISABLED("B000205", "用户不可用，请联系管理员"),
    PASSWORD_ERROR("B000206", "用户名或密码错误"),
    PASSWORD_MATCHED("B000207", "新密码与旧密码一致，请重新输入");

    private final String code;

    private final String message;

    UserErrorCodeEnum(String code, String message) {
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
