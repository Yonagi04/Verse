package com.yonagi.verse.async.event;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * LLM 调用审计事件 — 租户开启审计时由转发链路投递，消费者上传 S3 并落 t_llm_audit_log。
 *
 * @author Yonagi
 */
@Getter
@Setter
@NoArgsConstructor
public class LlmAuditEvent extends DomainEvent {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAIL = "FAIL";

    private String requestId;

    private Long userId;

    private Long tenantId;

    private Long apiKeyId;

    private Long serviceId;

    /**
     * 实际调用的模型别名
     */
    private String model;

    /**
     * 原始请求体（OpenAI 兼容 JSON）
     */
    private String prompt;

    /**
     * 原始响应体（OpenAI 兼容 JSON，失败时为 null）
     */
    private String response;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /**
     * 调用耗时（毫秒）
     */
    private Integer latencyMs;

    /**
     * SUCCESS / FAIL
     */
    private String status;

    /**
     * 失败时的错误码
     */
    private String errorCode;

    @Override
    public String eventType() {
        return EventTag.LLM_AUDIT;
    }
}
