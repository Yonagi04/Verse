package com.yonagi.verse.common.constant;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/12 14:33
 */
public class RedisKeyConstant {

    /**
     * 用户注册锁
     */
    public final static String LOCK_USER_REGISTER_KEY = "verse:lock_user-register:";

    /**
     * 用户登录会话key（{userId} → LoginSessionVO）
     */
    public final static String USER_LOGIN_KEY = "verse:login:";

    /**
     * 用户登录Token反向索引key（{tokenHash} → userId），用于登出时快速定位
     */
    public final static String USER_LOGIN_TOKEN_KEY = "verse:login:token:";

    /**
     * 用户手机号验证码key（{phone} -> code），用于找回密码场景
     */
    public final static String USER_PHONE_SENDING_CODE_KEY = "verse:reset-password:sending-code:";

    /**
     * 用户修改密码时的Token Key（{phone}->tokenHash），用于找回密码场景
     */
    public final static String USER_RESET_PHONE_TOKEN_KEY = "verse:reset-password:token:";
}
