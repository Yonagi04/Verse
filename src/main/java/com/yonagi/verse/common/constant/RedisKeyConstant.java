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
    public final static String USER_LOGIN_KEY = "verse:user:login:";

    /**
     * 用户登录Token反向索引key（{tokenHash} → userId），用于登出时快速定位
     */
    public final static String USER_LOGIN_TOKEN_KEY = "verse:user:login:token:";

    /**
     * 用户手机号验证码key（{phone} -> code），用于找回密码场景
     */
    public final static String USER_PHONE_SENDING_CODE_KEY = "verse:user:reset-password:sending-code:";

    /**
     * 用户修改密码时的Token Key（{phone}->tokenHash），用于找回密码场景
     */
    public final static String USER_RESET_PHONE_TOKEN_KEY = "verse:user:reset-password:token:";

    /**
     * 用户绑定手机号的SET（{phoneHash} -> userId），用于手机号去重场景
     */
    public final static String USER_PHONE_KEY = "verse:user:phone:";

    /**
     * 用户绑定邮箱的SET（{emailHash} -> userId），用于邮箱绑定场景
     */
    public final static String USER_EMAIL_COUNT_KEY = "verse:user:email-bind-count:";

    /**
     * 用户信息缓存key（{userId} → UserRespDTO JSON），用于getCurrentUser缓存
     */
    public final static String USER_PROFILE_KEY = "verse:user:profile:";

    /**
     * 用户信息缓存key（{userId} → UserInfoRespDTO JSON），用于getUserInfo缓存
     */
    public final static String USER_ANOTHER_PROFILE_KEY = "verse:user:another-profile:";

    /**
     * 用户注销账号验证码key（{userId} -> code），用于注销账号场景
     */
    public final static String USER_CLOSE_ACCOUNT_SENDING_CODE_KEY = "verse:user:close-account:sending-code:";
}
