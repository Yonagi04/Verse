package com.yonagi.verse.resilience.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 限流上下文 — 一次转发请求的三级维度 id 及其各自 RPM/TPM 阈值。
 *
 * <p>阈值来源：租户（t_tenant）、API Key（t_api_key）、模型（t_llm_service），
 * 值为 {@code null} 表示该维度不限流。</p>
 *
 * @author Yonagi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitContext {

    /**
     * 租户 ID
     */
    private Long tenantId;

    /**
     * API Key ID（业务 ID）
     */
    private Long apiKeyId;

    /**
     * 模型服务 ID（业务 ID）
     */
    private Long serviceId;

    /**
     * 租户级 RPM（NULL=不限）
     */
    private Integer tenantRpm;

    /**
     * 租户级 TPM（NULL=不限）
     */
    private Integer tenantTpm;

    /**
     * Key 级 RPM（NULL=不限）
     */
    private Integer apiKeyRpm;

    /**
     * Key 级 TPM（NULL=不限）
     */
    private Integer apiKeyTpm;

    /**
     * 模型级 RPM（NULL=不限）
     */
    private Integer modelRpm;

    /**
     * 模型级 TPM（NULL=不限）
     */
    private Integer modelTpm;
}
