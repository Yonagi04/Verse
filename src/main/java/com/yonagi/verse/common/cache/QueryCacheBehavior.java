package com.yonagi.verse.common.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 查询缓存的业务扩展点；实现为 Spring Bean 后由方法注解选择。 */
public interface QueryCacheBehavior {
    QueryCacheBehavior DEFAULT = new QueryCacheBehavior() { };

    default boolean supports(Object[] args) { return true; }

    /** 在统一身份转换前裁剪、规范化或补充缓存键参数。 */
    default List<Object> parameters(Object[] args) { return new ArrayList<>(Arrays.asList(args)); }

    /** 在统一身份转换后追加时间边界等业务维度。 */
    default void contributeParameters(List<Object> parameters, Object[] args) { }

    /** 在业务访问策略后执行与缓存内容相关的额外校验。 */
    default void check(Object[] args) { }

    default Object load(Object target, Object[] args, QueryCache.Loader original) throws Throwable {
        return original.load();
    }

    /** 命中和回源后均计算实时视图，避免冻结价格、使用时间和自然过期状态。 */
    default Object currentView(Object target, Object[] args, Object cached) { return cached; }

    default boolean cacheable(Object value) { return true; }

    /** 仅业务明确确认的资源不存在可负缓存，授权失败默认不缓存。 */
    default boolean cacheFailure(com.yonagi.verse.common.convention.exception.ClientException error) { return false; }
}
