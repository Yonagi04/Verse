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
     * 用户资料缓存（参数摘要:代际摘要 → UserRespDTO 缓存封装）
     */
    public final static String USER_PROFILE_KEY = "verse:user:profile:";

    /**
     * 公开资料缓存（参数摘要:代际摘要 → UserInfoRespDTO 缓存封装）
     */
    public final static String USER_PUBLIC_PROFILE_KEY = "verse:user:public-profile:";

    /**
     * 用户注销账号验证码key（{userId} -> code），用于注销账号场景
     */
    public final static String USER_CLOSE_ACCOUNT_SENDING_CODE_KEY = "verse:user:close-account:sending-code:";

    /**
     * 租户详情缓存（参数摘要:代际摘要 → TenantInfoResp 缓存封装）
     */
    public final static String TENANT_INFO_KEY = "verse:tenant:info:";

    /**
     * 邀请码及租户信息缓存（参数摘要:代际摘要 → 查询响应缓存封装）
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
     * 用户登录历史缓存（参数摘要:代际摘要 → LoginHistoryRespDTO 缓存封装）
     */
    public static final String USER_LOGIN_HISTORY_KEY = "verse:user:login-history:";

    /**
     * 路由索引（Hash）：{tenantId} → Hash(name → serviceId)，O(1) 定位
     */
    public static final String LLM_SERVICE_ROUTE_KEY = "verse:llm-service:route:";

    /**
     * 内部模型实体（参数摘要:代际摘要 → LlmServiceDO 缓存封装，apiKey 为密文）
     */
    public static final String LLM_SERVICE_INFO_KEY = "verse:llm-service:info:";

    /**
     * 租户启用服务列表（String JSON array）：{tenantId} → List<LlmServiceDO>（降级/列表查询）
     */
    public static final String LLM_SERVICE_LIST_KEY = "verse:llm-service:list:";

    /** 当前定价配置缓存（参数摘要:代际摘要 → 定价配置缓存封装）。 */
    public static final String LLM_SERVICE_PRICING_KEY = "verse:llm-service:pricing:";

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

    /**
     * 通知正文缓存（参数摘要:代际摘要 → NotificationDO 缓存封装）
     */
    public static final String NOTIFICATION_INFO_KEY = "verse:notification:info:";

    // 核心查询结果后缀统一为 参数摘要:依赖代际摘要，所有键前缀集中在本类。

    /** 用户租户列表缓存键。 */
    public static final String TENANT_LIST_KEY = "verse:tenant:list:";

    /** 租户设置缓存键。 */
    public static final String TENANT_SETTINGS_KEY = "verse:tenant:settings:";

    /** 批量租户概览缓存键。 */
    public static final String TENANT_OVERVIEW_LIST_KEY = "verse:tenant:overview:list:";

    /** 单租户概览缓存键。 */
    public static final String TENANT_OVERVIEW_INFO_KEY = "verse:tenant:overview:info:";

    /** 租户成员列表缓存键。 */
    public static final String TENANT_MEMBER_LIST_KEY = "verse:tenant:member:list:";

    /** 租户邀请码列表缓存键。 */
    public static final String TENANT_INVITE_LIST_KEY = "verse:tenant:invite:list:";

    /** 租户加入申请列表缓存键。 */
    public static final String TENANT_JOIN_REQUEST_LIST_KEY = "verse:tenant:join-request:list:";

    /** 待审核加入申请数量缓存键。 */
    public static final String TENANT_JOIN_REQUEST_UNREVIEWED_COUNT_KEY = "verse:tenant:join-request:unreviewed-count:";

    /** 租户动态开关状态缓存键。 */
    public static final String TENANT_ACTIVITY_STATUS_KEY = "verse:tenant:activity:status:";

    /** 租户动态列表缓存键。 */
    public static final String TENANT_ACTIVITY_LIST_KEY = "verse:tenant:activity:list:";

    /** 模型管理列表缓存，独立于内部启用模型列表。 */
    public static final String LLM_SERVICE_MANAGE_LIST_KEY = "verse:llm-service:manage:list:";

    /** 模型管理详情缓存，独立于内部模型实体。 */
    public static final String LLM_SERVICE_MANAGE_INFO_KEY = "verse:llm-service:manage:info:";

    /** 租户模型数量缓存键。 */
    public static final String LLM_SERVICE_COUNT_KEY = "verse:llm-service:count:";

    /** 模型标签列表缓存键。 */
    public static final String LLM_SERVICE_TAG_LIST_KEY = "verse:llm-service:tag:list:";

    /** OpenAI 兼容模型列表缓存键。 */
    public static final String LLM_SERVICE_OPENAI_MODELS_KEY = "verse:llm-service:openai:models:";

    /** Playground 模型列表缓存键。 */
    public static final String PLAYGROUND_MODELS_KEY = "verse:playground:models:";

    /** 工作台模型与分钟价格缓存键。 */
    public static final String PLAYGROUND_WORKBENCH_MODELS_KEY = "verse:playground:workbench:models:";

    /** 工作台预设列表缓存键。 */
    public static final String PLAYGROUND_PRESET_LIST_KEY = "verse:playground:preset:list:";

    /** 工作台预设详情缓存键。 */
    public static final String PLAYGROUND_PRESET_INFO_KEY = "verse:playground:preset:info:";

    /** 用户通知列表缓存键。 */
    public static final String NOTIFICATION_LIST_KEY = "verse:notification:list:";

    /** 未读通知数量缓存键。 */
    public static final String NOTIFICATION_UNREAD_COUNT_KEY = "verse:notification:unread-count:";

    /** 租户最近通知缓存键。 */
    public static final String NOTIFICATION_RECENT_LIST_KEY = "verse:notification:recent:list:";

    /** 租户审计列表缓存键。 */
    public static final String LLM_AUDIT_LIST_KEY = "verse:llm-audit:list:";

    /** 审计详情缓存键。 */
    public static final String LLM_AUDIT_INFO_KEY = "verse:llm-audit:info:";

    /** 用量概览与时间序列缓存键。 */
    public static final String USAGE_REPORT_QUERY_KEY = "verse:usage:report:query:";

    /** 用量维度拆分缓存键。 */
    public static final String USAGE_REPORT_BREAKDOWN_KEY = "verse:usage:report:breakdown:";

    /** 用量筛选项缓存键。 */
    public static final String USAGE_REPORT_FILTER_OPTIONS_KEY = "verse:usage:report:filter-options:";

    /** 用量仪表盘缓存键。 */
    public static final String USAGE_REPORT_DASHBOARD_KEY = "verse:usage:report:dashboard:";

    /** 模型协议与能力缓存键。 */
    public static final String LLM_SERVICE_PROTOCOL_KEY = "verse:llm-service:protocol:";

    /** 历史定价与高峰规则缓存键。 */
    public static final String LLM_SERVICE_PRICING_RULES_KEY = "verse:llm-service:pricing:rules:";

    /** 工作台资源分类缓存键。 */
    public static final String PLAYGROUND_WORKBENCH_KIND_KEY = "verse:playground:workbench:kind:";

    /** API Key 哈希到 ID 的定位缓存，授权状态每次实时复核。 */
    public static final String API_KEY_AUTH_ID_KEY = "verse:api-key:auth:id:";

    /** 用户在指定租户下的 Key 列表元数据，不含最近使用时间。 */
    public static final String API_KEY_LIST_KEY = "verse:api-key:list:";

    /** 核心查询重建锁：业务前缀摘要:参数摘要。 */
    public static final String CORE_QUERY_CACHE_LOCK_KEY = "verse:lock_core-query:";

    /** 核心查询依赖代际：表名。 */
    public static final String CORE_QUERY_CACHE_VERSION_KEY = "verse:query-cache:version:";

    /** 核心查询写入标记集合：表名，不自动过期。 */
    public static final String CORE_QUERY_CACHE_WRITERS_KEY = "verse:query-cache:writers:";

    /** 核心查询依赖索引有序集合：表名。 */
    public static final String CORE_QUERY_CACHE_INDEX_KEY = "verse:query-cache:index:";

    /** 查询缓存测试数据前缀，生产业务不使用。 */
    public static final String CORE_QUERY_CACHE_TEST_KEY = "verse:test:query-cache:";
}
