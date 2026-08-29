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
    public final static String USER_PUBLIC_PROFILE_KEY = "verse:user:public-profile:";

    /**
     * 用户注销账号验证码key（{userId} -> code），用于注销账号场景
     */
    public final static String USER_CLOSE_ACCOUNT_SENDING_CODE_KEY = "verse:user:close-account:sending-code:";

    /**
     * 租户信息缓存key（{tenantId} -> TenantInfoResp JSON），用于缓存
     */
    public final static String TENANT_INFO_KEY = "verse:tenant:info:";

    /**
     * 租户邀请码缓存key（{inviteCode}->TenantInviteDO JSON），用于缓存
     */
    public final static String TENANT_INVITE_CODE_KEY = "verse:tenant:invite-code:";

    /**
     * 用户-租户单条关系缓存key（{userId, tenantId}->UserTenantDO JSON）
     */
    public final static String USER_TENANT_RELATION_KEY = "verse:user-tenant:";

    /**
     * 租户关闭Token缓存key（{tenantId, userId}->closeToken），用于租户关闭场景
     */
    public final static String TENANT_CLOSE_TOKEN_KEY = "verse:tenant:close-token:";

    /**
     * 租户加入请求缓存key（{requestId}->TenantJoinRequestDO JSON），用于租户加入申请场景
     */
    public final static String TENANT_JOIN_REQUEST_KEY = "verse:tenant:join-request:";

    /**
     * 用户多设备会话映射：{userId} → Hash(deviceId → LoginSessionVO)
     */
    public static final String USER_DEVICES_KEY = "verse:user:devices:";

    /**
     * 用户登录历史记录：{userId}:{pageNum}:{pageSize} -> LoginHistoryRespDTO JSON
     */
    public static final String USER_LOGIN_HISTORY_KEY = "verse:user:login-history:";

    /**
     * 路由索引（Hash）：{tenantId} → Hash(name → serviceId)，O(1) 定位
     */
    public static final String LLM_SERVICE_ROUTE_KEY = "verse:llm-service:route:";

    /**
     * 服务详情（String JSON）：{serviceId} → LlmServiceDO（apiKey是加密的，需要自行解密）
     */
    public static final String LLM_SERVICE_INFO_KEY = "verse:llm-service:info:";

    /**
     * 租户启用服务列表（String JSON array）：{tenantId} → List<LlmServiceDO>（降级/列表查询）
     */
    public static final String LLM_SERVICE_LIST_KEY = "verse:llm-service:list:";

    /**
     * 删除llm服务的前置token key: {serviceId} -> token
     */
    public static final String LLM_REMOVE_TOKEN_KEY = "verse:llm-service:remove-token:";

    /**
     * 添加llm的锁，添加、更新时拿这个锁，{tenantId, name}
     */
    public static final String LLM_LOCK_KEY = "verse:lock_llm-service-add:";

    /**
     * API Key 认证缓存（{sha256} → ApiKeyDO JSON），用于 /api/v1/openai/** 鉴权
     */
    public static final String API_KEY_AUTH_KEY = "verse:api-key:auth:";

    /**
     * RPM 限流（Redisson RRateLimiter）：{dimension}:{id}，dimension ∈ tenant/key/model
     */
    public static final String RATE_LIMIT_RPM_KEY = "verse:ratelimit:rpm:";

    /**
     * TPM 软限流计数（String INCRBY）：{dimension}:{id}:{epochMinute}
     */
    public static final String RATE_LIMIT_TPM_KEY = "verse:ratelimit:tpm:";
}
