package com.yonagi.verse.common.cache;

import java.util.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;
import static com.yonagi.verse.common.cache.QueryCacheTtl.*;

/** 核心只读查询清单；新增接口必须显式声明权限与数据依赖。 */
public final class QueryCatalogue {
    private QueryCatalogue() { }
    public enum Access { NONE, TENANT, TEAM, ADMIN, PLAYGROUND, ACTIVITY, BATCH, REPORT, INVITE }
    public record Policy(String name, String keyPrefix, long seconds, Access access, List<String> tables) { }
    private static final Map<String, Policy> POLICIES = new LinkedHashMap<>();
    static {
        add("UserServiceImpl", "getCurrentUser", USER_PROFILE_KEY, HOURS_6, Access.NONE,
                "t_user,t_user_privacy");
        add("UserServiceImpl", "getUserInfo", USER_PUBLIC_PROFILE_KEY, HOURS_6, Access.NONE,
                "t_user,t_user_privacy");
        add("LoginHistoryServiceImpl", "getLoginHistoryList", USER_LOGIN_HISTORY_KEY, HOURS_1, Access.NONE,
                "t_user,t_login_history");
        add("TenantCrudServiceImpl", "listTenants", TENANT_LIST_KEY, HOURS_4, Access.NONE,
                "t_user,t_tenant,t_user_tenant");
        add("TenantCrudServiceImpl", "getTenantInfo", TENANT_INFO_KEY, HOURS_4, Access.TENANT,
                "t_tenant,t_user_tenant");
        add("TenantSettingsServiceImpl", "getSettings", TENANT_SETTINGS_KEY, HOURS_4, Access.TENANT,
                "t_tenant,t_user_tenant");
        add("TenantOverviewServiceImpl", "batch", TENANT_OVERVIEW_LIST_KEY, MINUTES_30, Access.BATCH,
                "t_tenant,t_user_tenant,t_llm_service,t_token_usage_hourly_agg,t_tenant_join_request");
        add("TenantOverviewServiceImpl", "detail", TENANT_OVERVIEW_INFO_KEY, MINUTES_30, Access.TENANT,
                "t_tenant,t_user_tenant,t_llm_service,t_token_usage_hourly_agg,t_tenant_join_request,t_tenant_activity_log");
        add("TenantMembershipServiceImpl", "listTenantMembers", TENANT_MEMBER_LIST_KEY, HOURS_4, Access.TEAM,
                "t_tenant,t_user_tenant,t_user");
        add("TenantInviteServiceImpl", "listTenantInviteCodes", TENANT_INVITE_LIST_KEY, MINUTES_30, Access.TEAM,
                "t_tenant,t_user_tenant,t_tenant_invite");
        add("TenantInviteServiceImpl", "getTenantAndInviteCodeInfo", TENANT_INVITE_CODE_KEY, HOURS_1, Access.INVITE,
                "t_tenant,t_tenant_invite");
        add("TenantApprovalServiceImpl", "listJoinRequests", TENANT_JOIN_REQUEST_LIST_KEY, MINUTES_30, Access.TEAM,
                "t_tenant,t_user_tenant,t_user,t_tenant_join_request");
        add("TenantApprovalServiceImpl", "getUnreviewedJoinReqCount", TENANT_JOIN_REQUEST_UNREVIEWED_COUNT_KEY, MINUTES_30, Access.TEAM,
                "t_tenant,t_user_tenant,t_user,t_tenant_join_request");
        add("TenantActivityQueryServiceImpl", "getStatus", TENANT_ACTIVITY_STATUS_KEY, HOURS_4, Access.TENANT,
                "t_tenant,t_user_tenant");
        add("TenantActivityQueryServiceImpl", "listActivities", TENANT_ACTIVITY_LIST_KEY, MINUTES_30, Access.ACTIVITY,
                "t_tenant,t_user_tenant,t_user,t_tenant_activity_log");
        add("ApiKeyServiceImpl", "listApiKeys", API_KEY_LIST_KEY, HOURS_4, Access.TENANT,
                "t_tenant,t_user_tenant,t_api_key");
        add("LlmManageServiceImpl", "listLlmService", LLM_SERVICE_MANAGE_LIST_KEY, HOURS_4, Access.TENANT,
                "t_tenant,t_user_tenant,t_user,t_llm_service,t_llm_service_capability,t_llm_service_tag,t_llm_tag,t_llm_service_pricing,t_llm_pricing_peak_period");
        add("LlmManageServiceImpl", "getLlmInfo", LLM_SERVICE_MANAGE_INFO_KEY, HOURS_4, Access.TENANT,
                "t_tenant,t_user_tenant,t_user,t_llm_service,t_llm_service_capability,t_llm_service_tag,t_llm_tag,t_llm_service_pricing,t_llm_pricing_peak_period");
        add("LlmManageServiceImpl", "getLlmServiceCount", LLM_SERVICE_COUNT_KEY, HOURS_4, Access.TENANT,
                "t_tenant,t_user_tenant,t_user,t_llm_service,t_llm_service_capability,t_llm_service_tag,t_llm_tag,t_llm_service_pricing,t_llm_pricing_peak_period");
        add("LlmManageServiceImpl", "listTags", LLM_SERVICE_TAG_LIST_KEY, HOURS_12, Access.NONE,
                "t_llm_tag");
        add("LlmForwardServiceImpl", "listModels", LLM_SERVICE_OPENAI_MODELS_KEY, HOURS_4, Access.NONE,
                "t_llm_service");
        add("PlaygroundServiceImpl", "models", PLAYGROUND_MODELS_KEY, HOURS_4, Access.PLAYGROUND,
                "t_tenant,t_user_tenant,t_llm_service,t_llm_service_capability");
        add("PlaygroundWorkbenchServiceImpl", "models", PLAYGROUND_WORKBENCH_MODELS_KEY, HOURS_4, Access.PLAYGROUND,
                "t_tenant,t_user_tenant,t_llm_service,t_llm_service_capability");
        add("PlaygroundWorkbenchServiceImpl", "list", PLAYGROUND_PRESET_LIST_KEY, HOURS_4, Access.PLAYGROUND,
                "t_tenant,t_user_tenant,t_playground_workspace");
        add("PlaygroundWorkbenchServiceImpl", "detail", PLAYGROUND_PRESET_INFO_KEY, HOURS_4, Access.PLAYGROUND,
                "t_tenant,t_user_tenant,t_playground_workspace");
        add("NotificationServiceImpl", "getNotificationList", NOTIFICATION_LIST_KEY, MINUTES_10, Access.NONE,
                "t_notification,t_notification_recipient");
        add("NotificationServiceImpl", "getUnreadNotificationCount", NOTIFICATION_UNREAD_COUNT_KEY, MINUTES_10, Access.NONE,
                "t_notification,t_notification_recipient");
        add("NotificationServiceImpl", "getRecentNotifications", NOTIFICATION_RECENT_LIST_KEY, MINUTES_10, Access.TENANT,
                "t_tenant,t_user_tenant,t_notification,t_notification_recipient");
        add("LlmAuditServiceImpl", "listAudit", LLM_AUDIT_LIST_KEY, MINUTES_30, Access.TENANT,
                "t_tenant,t_user_tenant,t_user,t_llm_audit_log");
        add("LlmAuditServiceImpl", "getAuditDetail", LLM_AUDIT_INFO_KEY, HOURS_24, Access.TENANT,
                "t_tenant,t_user_tenant,t_user,t_llm_audit_log");
        add("UsageReportServiceImpl", "query", USAGE_REPORT_QUERY_KEY, MINUTES_10, Access.REPORT,
                "t_tenant,t_user_tenant,t_user,t_api_key,t_llm_service,t_token_usage_hourly_agg");
        add("UsageReportServiceImpl", "breakdown", USAGE_REPORT_BREAKDOWN_KEY, MINUTES_10, Access.REPORT,
                "t_tenant,t_user_tenant,t_user,t_api_key,t_llm_service,t_token_usage_hourly_agg");
        add("UsageReportServiceImpl", "filterOptions", USAGE_REPORT_FILTER_OPTIONS_KEY, HOURS_1, Access.REPORT,
                "t_tenant,t_user_tenant,t_user,t_api_key,t_llm_service,t_token_usage_hourly_agg");
        add("UsageReportServiceImpl", "dashboard", USAGE_REPORT_DASHBOARD_KEY, MINUTES_10, Access.REPORT,
                "t_tenant,t_user_tenant,t_user,t_api_key,t_llm_service,t_token_usage_hourly_agg");
        add("ModelResolverImpl", "resolve", LLM_SERVICE_INFO_KEY, HOURS_4, Access.NONE,
                "t_llm_service");
        add("ModelResolverImpl", "protocolFor", LLM_SERVICE_PROTOCOL_KEY, HOURS_4, Access.NONE,
                "t_llm_service,t_llm_service_capability");
        add("PricingConfigurationService", "current", LLM_SERVICE_PRICING_KEY, HOURS_4, Access.NONE,
                "t_llm_service_pricing,t_llm_pricing_peak_period");
    }
    private static void add(String owner, String methods, String keyPrefix, long seconds, Access access, String tables) {
        List<String> dependencies = Arrays.stream(tables.split(",")).sorted().toList();
        for (String method : methods.split(",")) {
            String name = owner+"."+method;
            POLICIES.put(name, new Policy(name, keyPrefix, seconds, access, dependencies));
        }
    }
    public static Policy find(String owner, String method) { return POLICIES.get(owner+"."+method); }
    public static Collection<Policy> policies() { return Collections.unmodifiableCollection(POLICIES.values()); }
    public static boolean dependsOn(String table) {
        return Set.of("t_api_key", "t_notification").contains(table)
                || POLICIES.values().stream().anyMatch(p -> p.tables().contains(table));
    }
}
