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
        add("/api/v1/tenants/invites/{inviteCode}/info", "TenantInviteServiceImpl.getTenantAndInviteCodeInfo");
        add("/api/v1/tenants/{tenantId}/members", "TenantMembershipServiceImpl.listTenantMembers");
        add("/api/v1/tenants/{tenantId}/invites", "TenantInviteServiceImpl.listTenantInviteCodes");
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
            "/api/v1/api-keys/{tenantId}/list", "/api/v1/notifications/{notificationId}",
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
        for (String entry : CORE.values()) {
            int split = entry.lastIndexOf('.');
            assertNotNull(QueryCatalogue.find(entry.substring(0, split), entry.substring(split+1)), entry);
        }
        for (var policy : QueryCatalogue.policies()) {
            int split = policy.name().lastIndexOf('.');
            String owner = policy.name().substring(0, split), method = policy.name().substring(split+1);
            Class<?> type = null;
            for (String pkg : List.of("service.impl", "service.forward.impl", "service.pricing")) {
                try { type = Class.forName("com.yonagi.verse."+pkg+"."+owner); break; }
                catch (ClassNotFoundException ignored) { }
            }
            assertNotNull(type, policy.name());
            assertTrue(Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals(method)), policy.name());
            // 清单存在还不够，真实切点必须覆盖业务方法，避免收窄范围后静默丢失缓存。
            var pointcut = new org.springframework.aop.aspectj.AspectJExpressionPointcut();
            pointcut.setExpression(CoreQueryCacheAspect.class.getMethod("query", org.aspectj.lang.ProceedingJoinPoint.class)
                    .getAnnotation(org.aspectj.lang.annotation.Around.class).value());
            for (var target : type.getMethods()) {
                if (target.getName().equals(method)) assertTrue(pointcut.matches(target, type), policy.name());
            }
            assertFalse(policy.tables().isEmpty());
        }
    }
}
