package com.yonagi.verse.common.cache;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/** 启动时从方法注解收集查询策略与依赖，不再维护业务方法清单。 */
@Component
public final class QueryCatalogue implements BeanFactoryPostProcessor {
    public record Policy(String name, String keyPrefix, long seconds, Class<? extends QueryAccessPolicy> access, List<String> tables,
                         Class<? extends QueryCacheBehavior> behavior) { }
    private final Map<Method, Policy> policies = new LinkedHashMap<>();
    private final Set<String> dependencies = new HashSet<>();

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 只读取 Bean 类型，不创建业务服务，避免 MyBatis 拦截器与 Service 装配循环。
        for (String name : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(name, false);
            if (type != null) register(type);
        }
    }

    void register(Class<?> type) {
        QueryCacheDependencies manual = type.getAnnotation(QueryCacheDependencies.class);
        if (manual != null) {
            for (String table : manual.value()) {
                if (!table.matches("t_[a-z0-9_]+")) throw new IllegalStateException("查询缓存依赖表名无效: " + type);
                dependencies.add(table);
            }
        }
        for (Method method : type.getMethods()) {
            Policy policy = policy(method);
            if (policy == null) continue;
            if (Modifier.isFinal(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                    || Modifier.isFinal(type.getModifiers())) {
                throw new IllegalStateException("查询缓存方法必须可由 Spring 代理: " + method);
            }
            policies.put(method, policy);
            dependencies.addAll(policy.tables());
        }
        // 非公开方法无法被当前代理切点拦截，启动时拒绝静默遗漏。
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(QueryCached.class) && !Modifier.isPublic(method.getModifiers()))
                    throw new IllegalStateException("查询缓存方法必须公开: " + method);
            }
        }
    }

    public static Policy policy(Method method) {
        QueryCached annotation = method.getAnnotation(QueryCached.class);
        if (annotation == null) return null;
        if (annotation.keyPrefix().isBlank() || annotation.seconds() <= 0
                || annotation.seconds() > Long.MAX_VALUE / 1000 || annotation.tables().length == 0)
            throw new IllegalStateException("查询缓存配置不完整: " + method);
        List<String> tables = Arrays.stream(annotation.tables()).distinct().sorted().toList();
        if (tables.stream().anyMatch(table -> !table.matches("t_[a-z0-9_]+")))
            throw new IllegalStateException("查询缓存依赖表名无效: " + method);
        return new Policy(method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                annotation.keyPrefix(), annotation.seconds(), annotation.access(), tables, annotation.behavior());
    }

    public Map<Method, Policy> policies() { return Collections.unmodifiableMap(policies); }
    public boolean dependsOn(String table) { return dependencies.contains(table); }
    public Set<String> dependencies() { return Collections.unmodifiableSet(dependencies); }
}
