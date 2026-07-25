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

    THREAD_INTERRUPTED("A000200", "线程中断异常"),
    USER_SAVED_ERROR("A000201", "用户记录保存失败"),
    USER_UPDATE_ERROR("A000202", "用户记录更新失败"),

    USER_NOT_EXIST("B000200", "用户不存在"),
    USER_EXIST("B000201", "用户已存在"),
    USERNAME_EXIST("B000202", "用户名已存在"),
    EMAIL_BIND_COUNT_EXCEED("B000203", "邮箱绑定数量超过限制，单个邮箱最多只可绑定3个用户"),
    USER_STATUS_DISABLED("B000205", "用户不可用，请联系管理员"),
    PASSWORD_ERROR("B000206", "用户名或密码错误"),
    PASSWORD_MATCHED("B000207", "新密码与旧密码一致，请重新输入"),
    USER_HAS_BEEN_LOGIN("B000208", "用户已登录，请勿重复登录"),
    USER_PHONE_EXIST("B000209", "手机号已存在"),
    USER_PHONE_NOT_EXIST("B000210", "手机号不存在"),
    USER_PHONE_CODE_SEND_FREQUENT("B000211", "验证码发送过于频繁，请60秒后再试"),
    USER_PHONE_CODE_ERROR("B000212", "验证码错误"),
    USER_RESET_PASSWORD_FAIL("B000213", "密码重置失败，请重新尝试"),
    USER_ENCRYPT_ERROR("B000214", "数据加密失败"),
    USER_DECRYPT_ERROR("B000215", "数据解密失败"),
    USER_HASH_ERROR("B000216", "数据哈希失败"),
    PASSWORD_ERROR_FOR_RESET("B000217", "密码错误，请重新输入"),;

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
