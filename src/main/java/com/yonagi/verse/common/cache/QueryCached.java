package com.yonagi.verse.common.cache;

import java.lang.annotation.*;

/** 在 Spring Bean 的公开实现方法上声明查询缓存，无需修改中心清单。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface QueryCached {
    /** Redis 业务键前缀。 */
    String keyPrefix();
    /** 基础有效期，单位为秒。 */
    long seconds();
    /** 命中和回源前都要实时校验的访问权限。 */
    QueryCatalogue.Access access();
    /** 查询依赖的数据库表，写入时自动触发失效。 */
    String[] tables();
    /** 特殊业务策略；默认直接缓存方法返回值。 */
    Class<? extends QueryCacheBehavior> behavior() default QueryCacheBehavior.class;
}
