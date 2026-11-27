package com.yonagi.verse.common.cache;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ServerException;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 启动时从方法注解收集查询策略与依赖，不再维护业务方法清单。 */
@Component
public final class QueryCatalogue implements BeanFactoryPostProcessor, SmartInitializingSingleton {
    public record Policy(String name, String keyPrefix, long seconds,
                         Class<? extends QueryAccessPolicy> access, List<String> tables,
                         Class<? extends QueryCacheBehavior> behavior,
                         Class<? extends QueryDependencyResolver> dependencyResolver) { }
    private record MutationBinding(Set<String> tables, Class<? extends QueryMutationResolver> resolver) { }
    private final Map<Method, Policy> policies = new LinkedHashMap<>();
    private final Set<String> dependencies = new HashSet<>();
    private final Map<String, MutationBinding> mutations = new HashMap<>();
    private final QueryCacheProperties defaults = new QueryCacheProperties();
    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        // 只读取 Bean 类型，不创建业务服务，避免 MyBatis 拦截器与 Service 装配循环。
        for (String name : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(name, false);
            if (type != null) {
                register(type);
            }
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        configuredProperties();
        for (Policy policy : policies.values()) {
            if (policy.dependencyResolver() != QueryDependencyResolver.class) {
                requireSingleBean(policy.dependencyResolver());
            }
        }
        mutations.values().stream().map(MutationBinding::resolver).distinct().forEach(this::requireSingleBean);
    }

    void register(Class<?> type) {
        registerMutation(type);
        QueryCacheDependencies manual = type.getAnnotation(QueryCacheDependencies.class);
        if (manual != null) {
            for (String table : manual.value()) {
                if (!QueryDependency.validTable(table)) {
                    throw new IllegalStateException("查询缓存依赖表名无效: " + type);
                }
                dependencies.add(table);
            }
        }
        for (Method method : type.getMethods()) {
            Policy policy = policy(method);
            if (policy == null) {
                continue;
            }
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
                if (method.isAnnotationPresent(QueryCached.class) && !Modifier.isPublic(method.getModifiers())) {
                    throw new IllegalStateException("查询缓存方法必须公开: " + method);
                }
            }
        }
    }

    private void registerMutation(Class<?> type) {
        QueryMutationBinding binding = type.getAnnotation(QueryMutationBinding.class);
        if (binding == null) {
            return;
        }
        if (!QueryMutationResolver.class.isAssignableFrom(type) || type.isInterface()
                || Modifier.isAbstract(type.getModifiers()) || binding.tables().length == 0
                || binding.statementIds().length == 0) {
            throw new IllegalStateException("写缓存解析器元数据无效: " + type);
        }
        Set<String> tables = new HashSet<>(Arrays.asList(binding.tables()));
        for (String table : tables) {
            if (!QueryDependency.validTable(table)) {
                throw new IllegalStateException("写缓存绑定表名无效: " + type);
            }
        }
        dependencies.addAll(tables);
        Class<? extends QueryMutationResolver> resolver = type.asSubclass(QueryMutationResolver.class);
        for (String statementId : binding.statementIds()) {
            if (statementId.isBlank() || !statementId.contains(".")) {
                throw new IllegalStateException("写缓存绑定必须使用完整 statementId: " + type);
            }
            MutationBinding previous = mutations.putIfAbsent(statementId,
                    new MutationBinding(Set.copyOf(tables), resolver));
            if (previous != null) {
                throw new IllegalStateException("写缓存 statementId 重复绑定: " + statementId);
            }
        }
    }

    /** @return 当前写目标的完整保护集合，未绑定或显式全表影响均包含广播 */
    List<QueryDependency> mutationTargets(String statementId, String table, Object parameter) {
        QueryDependency legacy = QueryDependency.table(table);
        QueryCacheProperties properties = configuredProperties();
        if (properties.tableMode(table) == QueryCacheProperties.TableMode.LEGACY) {
            return List.of(legacy);
        }
        MutationBinding binding = mutations.get(statementId);
        try {
            if (binding == null) {
                return List.of(legacy, QueryDependency.broadcast(table));
            }
            if (!binding.tables().contains(table)) {
                throw new IllegalArgumentException("写缓存绑定与实际目标表不符");
            }
            List<QueryDependency> resolved = beanFactory.getBean(binding.resolver())
                    .resolve(statementId, table, parameter);
            if (resolved == null || resolved.isEmpty() || resolved.size() > properties.getMaxDependencies()) {
                throw new IllegalArgumentException("写缓存依赖为空或超过上限");
            }
            boolean broadcast = false;
            Set<QueryDependency> targets = new HashSet<>();
            for (QueryDependency dependency : resolved) {
                if (dependency == null || !table.equals(dependency.table()) || dependency.isBroadcast()) {
                    throw new IllegalArgumentException("写缓存解析包含异表或非法范围");
                }
                broadcast |= dependency.isTable();
                targets.add(dependency);
            }
            if (broadcast) {
                targets.clear();
                targets.add(QueryDependency.broadcast(table));
            }
            targets.add(legacy);
            if (targets.size() > properties.getMaxDependencies()) {
                throw new IllegalArgumentException("写缓存有效依赖超过上限");
            }
            return targets.stream().sorted(Comparator.comparing(QueryDependency::identity)).toList();
        } catch (RuntimeException error) {
            throw new ServerException("写缓存范围解析失败，已阻止 SQL 执行", error, BaseErrorCode.SERVICE_ERROR);
        }
    }

    boolean scopeEnabled(String table) {
        return configuredProperties().tableMode(table) != QueryCacheProperties.TableMode.LEGACY;
    }

    int maxDependencies() {
        return configuredProperties().getMaxDependencies();
    }

    private QueryCacheProperties configuredProperties() {
        QueryCacheProperties properties = beanFactory == null ? defaults
                : beanFactory.getBeanProvider(QueryCacheProperties.class).getIfAvailable(() -> defaults);
        if (properties.getScopeTableModes() == null) {
            throw new IllegalStateException("查询缓存表模式不能为空");
        }
        properties.getScopeTableModes().forEach((table, mode) -> {
            if (!QueryDependency.validTable(table) || !dependencies.contains(table) || mode == null) {
                throw new IllegalStateException("查询缓存表模式包含未登记表或非法阶段");
            }
        });
        return properties;
    }

    private void requireSingleBean(Class<?> type) {
        if (beanFactory != null && beanFactory.getBeanNamesForType(type, true, false).length != 1) {
            throw new IllegalStateException("缓存解析器必须对应唯一 Bean: " + type.getName());
        }
    }

    public static Policy policy(Method method) {
        QueryCached annotation = method.getAnnotation(QueryCached.class);
        if (annotation == null) {
            return null;
        }
        if (annotation.keyPrefix().isBlank() || annotation.seconds() <= 0
                || annotation.seconds() > Long.MAX_VALUE / 1000 || annotation.tables().length == 0) {
            throw new IllegalStateException("查询缓存配置不完整: " + method);
        }
        List<String> tables = Arrays.stream(annotation.tables()).distinct().sorted().toList();
        if (tables.stream().anyMatch(table -> !QueryDependency.validTable(table))) {
            throw new IllegalStateException("查询缓存依赖表名无效: " + method);
        }
        Class<? extends QueryDependencyResolver> resolver = annotation.dependencyResolver();
        if (resolver != QueryDependencyResolver.class
                && (resolver.isInterface() || Modifier.isAbstract(resolver.getModifiers()))) {
            throw new IllegalStateException("查询缓存依赖解析器必须为具体类型: " + method);
        }
        return new Policy(method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                annotation.keyPrefix(), annotation.seconds(), annotation.access(), tables,
                annotation.behavior(), resolver);
    }

    public Map<Method, Policy> policies() { return Collections.unmodifiableMap(policies); }
    public boolean dependsOn(String table) { return dependencies.contains(table); }
    public Set<String> dependencies() { return Collections.unmodifiableSet(dependencies); }
}
