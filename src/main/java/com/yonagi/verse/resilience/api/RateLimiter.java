package com.yonagi.verse.resilience.api;

/**
 * 限流器 — 业务唯一依赖的限流抽象，屏蔽具体实现（Redisson RRateLimiter / Redis 计数）。
 *
 * <p>RPM 为硬限流（请求前精确拦截）；TPM 为软限流（请求前读已结算计数、请求后结算），
 * 两者语义不同，故拆成 {@link #check} 与 {@link #settle} 两段。</p>
 *
 * @author Yonagi
 */
public interface RateLimiter {

    /**
     * 请求前检查：按「租户 → Key → 模型」顺序逐级检查 RPM 与 TPM，
     * 任一维度超限抛 {@code ClientException(A000802)}。
     *
     * @param ctx 三级维度及阈值
     */
    void check(RateLimitContext ctx);

    /**
     * 请求后结算：按真实 usage 累加到三级 TPM 计数（同步，保证「拦后续请求」及时生效）。
     *
     * @param ctx         三级维度及阈值
     * @param totalTokens 本次实际消耗 token 数
     */
    void settle(RateLimitContext ctx, int totalTokens);
}
