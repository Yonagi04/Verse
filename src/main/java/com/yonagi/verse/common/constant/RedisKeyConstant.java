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
}
