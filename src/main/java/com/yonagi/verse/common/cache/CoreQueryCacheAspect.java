package com.yonagi.verse.common.cache;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.dto.resp.TenantInviteListRespDTO;
import com.yonagi.verse.dto.resp.ApiKeyPageRespDTO;
import com.yonagi.verse.service.impl.ApiKeyServiceImpl;
import com.yonagi.verse.service.impl.PlaygroundWorkbenchServiceImpl;
import com.yonagi.verse.service.impl.TenantInviteServiceImpl;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 核心查询缓存切面，原控制器契约和业务权限校验保持有效。 */
@Aspect
@Component
@Order(100)
@RequiredArgsConstructor
public class CoreQueryCacheAspect {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final QueryCache cache;
    private final QueryAccessGuard guard;
    private final ThreadLocal<Boolean> building = ThreadLocal.withInitial(() -> false);

    // 只匹配业务查询实现；供应商适配器含 final 类，不能因缓存切面触发 CGLIB 代理。
    @Around("execution(public * com.yonagi.verse.service.impl.*.*(..))"
            + " || execution(public * com.yonagi.verse.service.forward.impl.ModelResolverImpl.*(..))"
            + " || execution(public * com.yonagi.verse.service.pricing.PricingConfigurationService.*(..))")
    public Object query(ProceedingJoinPoint point) throws Throwable {
        Method method = AopUtils.getMostSpecificMethod(((MethodSignature) point.getSignature()).getMethod(), point.getTarget().getClass());
        QueryCatalogue.Policy policy = QueryCatalogue.find(point.getTarget().getClass().getSimpleName(), method.getName());
        // 事务中的读取必须看到本事务修改，也不能等待本事务持有的写栅栏。
        if (policy == null || building.get() || TransactionSynchronizationManager.isActualTransactionActive()) return point.proceed();
        Object[] args = point.getArgs();
        if (policy.name().equals("PlaygroundWorkbenchServiceImpl.list") && !"PRESET".equals(args[2])) return point.proceed();
        if (policy.name().equals("PlaygroundWorkbenchServiceImpl.detail") && !guard.isPreset(args)) return point.proceed();
        List<Object> parameters = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            // 邀请码缓存候选集合，所有分页共享同一份候选；读取后实时过滤并分页。
            if (policy.name().equals("TenantInviteServiceImpl.listTenantInviteCodes") && i >= 2) continue;
            Object argument = args[i];
            if (policy.name().equals("PlaygroundWorkbenchServiceImpl.list") && i == 3 && argument instanceof String keyword) {
                // 与业务搜索的空白处理保持一致，等价关键字复用同一个键。
                argument = keyword.isBlank() ? null : keyword.trim();
            }
            if (argument instanceof UserContext context) {
                // 不把 Token 或安全凭证放入缓存键或缓存数据。
                JSONObject identity = new JSONObject();
                identity.put("user", context.getUserId()); identity.put("tenant", context.getCurrentTenantId());
                identity.put("role", context.getRole()); parameters.add(identity);
            } else parameters.add(argument);
        }
        if (policy.access() == QueryCatalogue.Access.BATCH) parameters.add(guard.batchIdentity(args));
        addTimeBoundary(parameters, policy, args);
        parameters.add(method.toGenericString());
        long ttl = policy.seconds()*1000;
        java.util.concurrent.atomic.AtomicBoolean healthy = new java.util.concurrent.atomic.AtomicBoolean(true);
        Object cached = cache.get(policy.name(), policy.keyPrefix(), parameters, method.getGenericReturnType(), policy.tables(), ttl,
                () -> guarded(policy, args), () -> {
                    building.set(true);
                    QueryCacheHealth.clear();
                    try {
                        Object result;
                        if (policy.name().equals("PlaygroundWorkbenchServiceImpl.models")) {
                            result = ((PlaygroundWorkbenchServiceImpl) point.getTarget()).modelMetadata((UserContext) args[0], (Long) args[1]);
                        } else if (policy.name().equals("TenantInviteServiceImpl.listTenantInviteCodes")) {
                            result = ((TenantInviteServiceImpl) point.getTarget()).inviteCandidates((Long) args[0], (Long) args[1]);
                        } else if (policy.name().equals("ApiKeyServiceImpl.listApiKeys")) {
                            result = ((ApiKeyServiceImpl) point.getTarget()).listApiKeyMetadata(
                                    (Long) args[0], (Long) args[1], (Integer) args[2], (Integer) args[3]);
                        } else result = point.proceed();
                        healthy.set(!QueryCacheHealth.isDegraded());
                        return result;
                    } finally { building.remove(); QueryCacheHealth.clear(); }
                }, result -> healthy.get() && cacheable(policy, result));
        return currentView(policy, point.getTarget(), args, cached);
    }

    /** 仅时间决定查询范围的业务分桶；资料、关系、模型和预设不因整点自动冷启动。 */
    private void addTimeBoundary(List<Object> parameters, QueryCatalogue.Policy policy, Object[] args) {
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        if (policy.name().startsWith("TenantOverviewServiceImpl.")) {
            parameters.add(now.toLocalDate().toString());
        } else if (policy.name().equals("UsageReportServiceImpl.dashboard")
                || policy.name().startsWith("NotificationServiceImpl.")) {
            parameters.add(now.truncatedTo(ChronoUnit.HOURS).toString());
        } else if ((policy.name().equals("UsageReportServiceImpl.query")
                || policy.name().equals("UsageReportServiceImpl.breakdown")) && args[4] == null) {
            // 显式结束时间的报表不需要分桶；默认窗口只在对应业务边界切换。
            UsageGranularity granularity = (UsageGranularity) args[2];
            parameters.add(switch (granularity) {
                case HOUR -> now.truncatedTo(ChronoUnit.HOURS).toString();
                case DAY -> now.toLocalDate().toString();
                case WEEK -> now.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
                case MONTH -> now.toLocalDate().withDayOfMonth(1).toString();
            });
        }
    }

    @SuppressWarnings("unchecked")
    private Object currentView(QueryCatalogue.Policy policy, Object target, Object[] args, Object cached) {
        if (policy.name().equals("PlaygroundWorkbenchServiceImpl.models")) {
            return ((PlaygroundWorkbenchServiceImpl) target).withCurrentPrices((Long) args[1], (List<JSONObject>) cached);
        }
        if (policy.name().equals("TenantInviteServiceImpl.listTenantInviteCodes")) {
            return ((TenantInviteServiceImpl) target).pageAvailableInvites((TenantInviteListRespDTO) cached,
                    (Integer) args[2], (Integer) args[3]);
        }
        if (policy.name().equals("ApiKeyServiceImpl.listApiKeys")) {
            // 高频使用时间只按当前页批量回源，不让每次模型调用清空稳定的列表缓存。
            return cache.check(() -> ((ApiKeyServiceImpl) target).withCurrentListState(
                    (Long) args[0], (Long) args[1], (ApiKeyPageRespDTO) cached));
        }
        return cached;
    }

    private void guarded(QueryCatalogue.Policy policy, Object[] args) {
        building.set(true);
        try { guard.check(policy, args); } finally { building.remove(); }
    }

    static boolean cacheable(QueryCatalogue.Policy policy, Object value) {
        if (!policy.name().startsWith("TenantOverviewServiceImpl.")) return true;
        JSONObject result = JSON.parseObject(JSON.toJSONString(value));
        if (result == null) return false;
        if (result.containsKey("items")) {
            return result.getJSONArray("items").stream().allMatch(item -> complete((JSONObject) item));
        }
        return complete(result);
    }

    private static boolean complete(JSONObject value) {
        // 聚合发生降级时不把缺失指标冻结到缓存中。
        return value.get("memberCount") != null && value.get("availableServiceCount") != null;
    }
}
