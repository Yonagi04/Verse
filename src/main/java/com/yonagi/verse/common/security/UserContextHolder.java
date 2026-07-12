package com.yonagi.verse.common.security;

/**
 * ThreadLocal 持有者 — 存储和清理当前线程的用户上下文
 *
 * @author Yonagi
 */
public class UserContextHolder {

    private static final ThreadLocal<UserContext> CONTEXT = new ThreadLocal<>();

    public static void set(UserContext ctx) {
        CONTEXT.set(ctx);
    }

    public static UserContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
