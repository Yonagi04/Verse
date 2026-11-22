package com.yonagi.verse.common.cache;

import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 方法注解驱动的通用查询缓存，特殊业务通过策略扩展。 */
@Aspect
@Component
@Order(100)
@RequiredArgsConstructor
public class CoreQueryCacheAspect {
    private final QueryCache cache;
    private final BeanFactory beanFactory;
    private final ThreadLocal<Boolean> building = ThreadLocal.withInitial(() -> false);

    // 仅代理显式声明缓存的方法，不再按包或服务类名匹配，避免代理无关的 final 适配器。
    @Around("execution(public * *(..)) && @annotation(com.yonagi.verse.common.cache.QueryCached)")
    public Object query(ProceedingJoinPoint point) throws Throwable {
        Method method = AopUtils.getMostSpecificMethod(((MethodSignature) point.getSignature()).getMethod(),
                point.getTarget().getClass());
        QueryCatalogue.Policy policy = QueryCatalogue.policy(method);
        // 事务中的读取必须看到本事务修改，也不能等待本事务持有的写栅栏。
        if (policy == null || building.get()
                || TransactionSynchronizationManager.isActualTransactionActive()) {
            return point.proceed();
        }
        QueryCacheBehavior behavior = policy.behavior() == QueryCacheBehavior.class
                ? QueryCacheBehavior.DEFAULT : beanFactory.getBean(policy.behavior());
        QueryAccessPolicy access = policy.access() == NoQueryAccess.class
                ? NoQueryAccess.INSTANCE : beanFactory.getBean(policy.access());
        Object[] args = point.getArgs();
        if (!behavior.supports(args)) {
            return point.proceed();
        }
        List<Object> parameters = new ArrayList<>();
        for (Object argument : behavior.parameters(args)) {
            if (argument instanceof UserContext context) {
                // 不把 Token 或安全凭证放入缓存键或缓存数据。
                JSONObject identity = new JSONObject();
                identity.put("user", context.getUserId()); identity.put("tenant", context.getCurrentTenantId());
                identity.put("role", context.getRole()); parameters.add(identity);
            } else {
                parameters.add(argument);
            }
        }
        cache.check(() -> { access.contributeParameters(parameters, args); return null; });
        behavior.contributeParameters(parameters, args);
        parameters.add(method.toGenericString());
        long ttl = policy.seconds()*1000;
        AtomicBoolean healthy = new AtomicBoolean(true);
        QueryCache.Loader loader = () -> {
                    building.set(true);
                    QueryCacheHealth.clear();
                    try {
                        Object result = behavior.load(point.getTarget(), args, point::proceed);
                        healthy.set(!QueryCacheHealth.isDegraded());
                        return result;
                    } finally { building.remove(); QueryCacheHealth.clear(); }
                };
        Object cached;
        if (policy.dependencyResolver() == QueryDependencyResolver.class) {
            cached = cache.get(policy.name(), policy.keyPrefix(), parameters, method.getGenericReturnType(),
                    policy.tables(), ttl, () -> guarded(access, behavior, args), loader,
                    result -> healthy.get() && behavior.cacheable(result), behavior::cacheFailure);
        } else {
            List<QueryDependency> dependencies;
            try {
                dependencies = QueryCacheKey.dependencies(beanFactory.getBean(policy.dependencyResolver())
                        .resolve(args.clone()), policy.tables(), beanFactory.getBean(QueryCacheProperties.class)
                        .getMaxDependencies());
            } catch (RuntimeException error) {
                throw new ServerException("查询缓存依赖解析失败", error, BaseErrorCode.SERVICE_ERROR);
            }
            cached = cache.getScoped(policy.name(), policy.keyPrefix(), parameters, method.getGenericReturnType(),
                    dependencies, ttl, () -> guarded(access, behavior, args), loader,
                    result -> healthy.get() && behavior.cacheable(result), behavior::cacheFailure);
        }
        return behavior.currentView(point.getTarget(), args, cached);
    }

    private void guarded(QueryAccessPolicy access, QueryCacheBehavior behavior, Object[] args) {
        building.set(true);
        try { access.check(args); behavior.check(args); } finally { building.remove(); }
    }
}
