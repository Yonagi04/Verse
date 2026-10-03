package com.yonagi.verse.common.cache;

/** 查询聚合降级时禁止缓存残缺结果；不影响原响应契约。 */
public final class QueryCacheHealth {
    private static final ThreadLocal<Boolean> DEGRADED = new ThreadLocal<>();
    private QueryCacheHealth() { }
    public static void degraded() { DEGRADED.set(true); }
    static boolean isDegraded() { return Boolean.TRUE.equals(DEGRADED.get()); }
    static void clear() { DEGRADED.remove(); }
}
