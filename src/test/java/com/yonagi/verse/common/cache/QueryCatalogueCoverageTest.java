package com.yonagi.verse.common.cache;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 所有 GET 必须显式分类，新增核心接口不能静默遗漏缓存策略。 */
class QueryCatalogueCoverageTest {
    private static final Map<String, String> CORE = new LinkedHashMap<>();
    static {
        add("/api/v1/users/me", "UserServiceImpl.getCurrentUser");
        add("/api/v1/users/getUserInfo", "UserServiceImpl.getUserInfo");
        add("/api/v1/users/me/login-history", "LoginHistoryServiceImpl.getLoginHistoryList");
        add("/api/v1/tenants", "TenantCrudServiceImpl.listTenants");
        add("/api/v1/tenants/{tenantId}/info", "TenantCrudServiceImpl.getTenantInfo");
        add("/api/v1/tenants/{tenantId}/settings", "TenantSettingsServiceImpl.getSettings");
        add("/api/v1/tenants/{tenantId}/activities/status", "TenantActivityQueryServiceImpl.getStatus");
        add("/api/v1/tenants/{tenantId}/activities", "TenantActivityQueryServiceImpl.listActivities");
        add("/api/v1/api-keys/{tenantId}/list", "ApiKeyServiceImpl.listApiKeys");
        add("/api/v1/tenants/invites/{inviteCode}/info", "TenantInviteServiceImpl.getTenantAndInviteCodeInfo");
        add("/api/v1/tenants/{tenantId}/members", "TenantMembershipServiceImpl.listTenantMembers");
        add("/api/v1/tenants/{tenantId}/join-requests", "TenantApprovalServiceImpl.listJoinRequests");
        add("/api/v1/tenants/{tenantId}/join-requests/unreviewed-count", "TenantApprovalServiceImpl.getUnreviewedJoinReqCount");
        add("/api/v1/tenants/overview", "TenantOverviewServiceImpl.batch");
        add("/api/v1/tenants/{tenantId}/overview", "TenantOverviewServiceImpl.detail");
        add("/api/v1/llm-service/{tenantId}/list", "LlmManageServiceImpl.listLlmService");
        add("/api/v1/llm-service/tags", "LlmManageServiceImpl.listTags");
        add("/api/v1/llm-service/{tenantId}/info/{serviceId}", "LlmManageServiceImpl.getLlmInfo");
        add("/api/v1/llm-service/{tenantId}/get-llm-count", "LlmManageServiceImpl.getLlmServiceCount");
        add("/api/v1/notifications", "NotificationServiceImpl.getNotificationList");
        add("/api/v1/notifications/unread-count", "NotificationServiceImpl.getUnreadNotificationCount");
        add("/api/v1/notifications/recent", "NotificationServiceImpl.getRecentNotifications");
        add("/api/v1/tenants/{tenantId}/playground/models", "PlaygroundServiceImpl.models");
        add("/api/v1/tenants/{tenantId}/playground/workbench/models", "PlaygroundWorkbenchServiceImpl.models");
        add("/api/v1/tenants/{tenantId}/playground/workbench/{kind:groups|presets}", "PlaygroundWorkbenchServiceImpl.list");
        add("/api/v1/tenants/{tenantId}/playground/workbench/{kind:groups|presets}/{id}", "PlaygroundWorkbenchServiceImpl.detail");
        add("/api/v1/openai/models", "LlmForwardServiceImpl.listModels");
        add("/api/v1/audit/{tenantId}/list", "LlmAuditServiceImpl.listAudit");
        add("/api/v1/audit/{tenantId}/detail/{auditId}", "LlmAuditServiceImpl.getAuditDetail");
        add("/api/v1/usage/{tenantId}/dashboard", "UsageReportServiceImpl.dashboard");
        add("/api/v1/usage/{tenantId}/overview", "UsageReportServiceImpl.query");
        add("/api/v1/usage/{tenantId}/timeseries", "UsageReportServiceImpl.query");
        add("/api/v1/usage/{tenantId}/breakdown", "UsageReportServiceImpl.breakdown");
        add("/api/v1/usage/{tenantId}/filters", "UsageReportServiceImpl.filterOptions");
    }
    private static void add(String path, String policy) { CORE.put(path, policy); }
    private static final Set<String> EXCEPTIONS = Set.of(
            "/api/v1/users/hasUsername", "/api/v1/users/logout", "/api/v1/users/account/cancel/prepare",
            "/api/v1/users/me/devices", "/api/v1/users/me/external-accounts", "/api/v1/auth/external/providers",
            "/api/v1/auth/external/callback/{provider}", "/api/v1/auth/external/flows/{id}",
            "/api/v1/tenants/{tenantId}/playground/status", "/api/v1/tenants/{tenantId}/playground/prompts",
            "/api/v1/tenants/{tenantId}/playground/sessions", "/api/v1/tenants/{tenantId}/playground/sessions/{sessionId}",
            "/api/v1/notifications/{notificationId}",
            // 邀请自然过期需要实时筛选；只查当前页，不缓存整个租户候选集合。
            "/api/v1/tenants/{tenantId}/invites",
            // 实时成本快照参与熔断判断，不允许通过查询缓存返回旧状态。
            "/api/v1/api-keys/{tenantId}/{apiKeyId}/cost-status",
            "/api/v1/usage/{tenantId}/export", "/api/v1/usage-events/{tenantId}/reconciliation");

    @Test void everyCoreGetHasPolicyAndEveryGetIsClassified() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> routes = new TreeSet<>();
        for (var bean : scanner.findCandidateComponents("com.yonagi.verse.controller")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            RequestMapping base = type.getAnnotation(RequestMapping.class);
            for (var method : type.getDeclaredMethods()) {
                GetMapping mapping = method.getAnnotation(GetMapping.class);
                if (mapping == null) continue;
                String[] paths = mapping.value().length == 0 ? new String[]{""} : mapping.value();
                for (String path : paths) routes.add(base.value()[0] + path);
            }
        }
        Set<String> classified = new TreeSet<>(CORE.keySet()); classified.addAll(EXCEPTIONS);
        assertEquals(classified, routes, "新增 GET 必须明确缓存策略或业务例外");
        assertEquals(34, CORE.size());
        QueryCatalogue catalogue = QueryCacheTestSupport.catalogue();
        for (String entry : CORE.values()) {
            assertTrue(catalogue.policies().values().stream().anyMatch(policy -> policy.name().equals(entry)), entry);
        }
        var pointcut = new org.springframework.aop.aspectj.AspectJExpressionPointcut();
        pointcut.setExpression(CoreQueryCacheAspect.class.getMethod("query", org.aspectj.lang.ProceedingJoinPoint.class)
                .getAnnotation(org.aspectj.lang.annotation.Around.class).value());
        for (var entry : catalogue.policies().entrySet()) {
            var method = entry.getKey();
            var policy = entry.getValue();
            // 直接使用发现的方法元数据，新实现类或子包不需要再扩展测试中的类名查找清单。
            assertTrue(pointcut.matches(method, method.getDeclaringClass()), policy.name());
            assertFalse(policy.tables().isEmpty());
        }
    }
}
