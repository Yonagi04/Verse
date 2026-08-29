package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;

/**
 * 上游调用失败异常 — 在 {@link ClientException} 基础上标记是否「可重试」，
 * 供熔断记录与降级判断区分：可重试失败（超时 / 5xx / 429）触发熔断记录与降级；
 * 不可重试失败（如上游 4xx 业务错误）直接透传，不影响熔断健康度。
 *
 * @author Yonagi
 */
public class UpstreamFailureException extends ClientException {

    /**
     * 是否可重试（超时 / 5xx / 429 为 true，其余 4xx 为 false）
     */
    private final boolean retryable;

    public UpstreamFailureException(String message, IErrorCode errorCode, boolean retryable) {
        super(message, errorCode);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
