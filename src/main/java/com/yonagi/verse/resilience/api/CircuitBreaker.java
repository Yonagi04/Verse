package com.yonagi.verse.resilience.api;

/**
 * 熔断器 — 业务唯一依赖的熔断抽象，按 serviceId 维护独立的模型级熔断状态。
 *
 * <p>由编排层显式调用 {@link #isOpen} 判断是否快速失败，并在上游调用成功后/可重试失败后
 * 分别调用 {@link #recordSuccess}/{@link #recordFailure} 驱动状态机。</p>
 *
 * @author Yonagi
 */
public interface CircuitBreaker {

    /**
     * 判断指定模型的熔断器是否处于打开状态（打开期间应快速失败，不转发上游）。
     *
     * @param serviceId 模型服务 ID
     * @return true 表示熔断打开
     */
    boolean isOpen(String serviceId);

    /**
     * 记录一次成功调用。
     *
     * @param serviceId 模型服务 ID
     */
    void recordSuccess(String serviceId);

    /**
     * 记录一次可重试失败（超时 / 5xx / 429）。
     *
     * @param serviceId 模型服务 ID
     */
    void recordFailure(String serviceId);
}
