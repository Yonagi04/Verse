package com.yonagi.verse.common.cache;

/** 显式声明此查询无需额外访问校验；调用方仍负责认证上下文。 */
public final class NoQueryAccess implements QueryAccessPolicy {
    public static final NoQueryAccess INSTANCE = new NoQueryAccess();
    private NoQueryAccess() { }
    @Override public void check(Object[] arguments) { }
}
