package com.yonagi.verse.common.cache;

import java.util.List;

/** 业务提供查询依赖；实现必须无状态，不读取 DAO、登录线程上下文或 Redis。 */
public interface QueryDependencyResolver {
    /** @return 覆盖注解全部 tables 的非空依赖，不得包含内部广播范围 */
    List<QueryDependency> resolve(Object[] arguments);
}
