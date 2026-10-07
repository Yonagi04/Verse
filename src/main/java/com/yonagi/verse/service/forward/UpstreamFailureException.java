package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;

/**
 * 上游失败的原因与执行证据。临时错误不等于可以安全重发，重试需通过执行状态策略。
 *
 * @author Yonagi
 */
public class UpstreamFailureException extends ClientException {

    /**
     * 是否为重试候选；结果未知时仍禁止重发。
     */
    private final boolean retryable;

    /** 能够证明的上游执行状态。 */
    private final UpstreamExecutionOutcome executionOutcome;

    /** 是否影响上游健康度；调用方取消和本机容量拒绝不能归咎于上游。 */
    private final boolean upstreamHealthFailure;

    public UpstreamFailureException(String message, IErrorCode errorCode, boolean retryable) {
        this(message, errorCode, retryable, UpstreamExecutionOutcome.UNKNOWN);
    }

    public UpstreamFailureException(String message, IErrorCode errorCode, boolean retryable,
                                    UpstreamExecutionOutcome executionOutcome) {
        this(message, errorCode, retryable, executionOutcome,
                retryable || executionOutcome == UpstreamExecutionOutcome.UNKNOWN);
    }

    public UpstreamFailureException(String message, IErrorCode errorCode, boolean retryable,
                                    UpstreamExecutionOutcome executionOutcome, boolean upstreamHealthFailure) {
        this(message, errorCode, retryable, executionOutcome, upstreamHealthFailure, null);
    }

    public UpstreamFailureException(String message, IErrorCode errorCode, boolean retryable,
                                    UpstreamExecutionOutcome executionOutcome, boolean upstreamHealthFailure,
                                    Throwable cause) {
        super(message, cause, errorCode);
        this.retryable = retryable;
        this.executionOutcome = java.util.Objects.requireNonNull(executionOutcome);
        this.upstreamHealthFailure = upstreamHealthFailure;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public UpstreamExecutionOutcome getExecutionOutcome() { return executionOutcome; }

    public boolean isUpstreamHealthFailure() {
        return upstreamHealthFailure;
    }
}
