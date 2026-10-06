package com.yonagi.verse.common.cache;

import java.util.List;

/** 业务提供实时校验；缓存只负责调用时机和并发保护。 */
public interface QueryAccessPolicy {
    void check(Object[] arguments);

    /** 业务自行声明影响结果的实时隔离维度，不缓存授权结论。 */
    default void contributeParameters(List<Object> parameters, Object[] arguments) { }
}
