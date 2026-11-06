package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;

/** 上游错误只公开本地安全文案，任意 Provider 正文均不能成为错误响应或日志。 */
public final class UpstreamErrors {
    private UpstreamErrors() {}

    /** 根据 HTTP 状态保留执行证据和重试分类；response 仅为兼容旧调用签名，不解析或保留。 */
    public static UpstreamFailureException from(int status, String response) {
        return new UpstreamFailureException(LlmForwardErrorCodeEnum.UPSTREAM_ERROR.message(),
                LlmForwardErrorCodeEnum.UPSTREAM_ERROR,
                status == 429 || status >= 500,
                status >= 500 || status == 408 || status == 409
                        ? UpstreamExecutionOutcome.UNKNOWN : UpstreamExecutionOutcome.REJECTED);
    }

    public static UpstreamFailureException timeout() {
        return new UpstreamFailureException(LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT.message(),
                LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT, false, UpstreamExecutionOutcome.UNKNOWN);
    }

    /** HTTP/SDK 流结束不能替代模型协议结束；缺少结束证据时保留未知结果。 */
    public static UpstreamFailureException incompleteStream() {
        return new UpstreamFailureException("上游事件流缺少结束标记",
                LlmForwardErrorCodeEnum.UPSTREAM_ERROR, false, UpstreamExecutionOutcome.UNKNOWN);
    }

    /** 仅用于已收到上游响应后的转换失败，不将本地处理失败解释为未发送。 */
    public static UpstreamFailureException responseFailure(RuntimeException error) {
        if (error instanceof UpstreamFailureException known) return known;
        return new UpstreamFailureException(LlmForwardErrorCodeEnum.FORWARD_FAILED.message(),
                LlmForwardErrorCodeEnum.FORWARD_FAILED, false, UpstreamExecutionOutcome.UNKNOWN, true, error);
    }

    public static UpstreamFailureException cancelled(UpstreamExecutionOutcome outcome) {
        return new UpstreamFailureException(LlmForwardErrorCodeEnum.FORWARD_FAILED.message(),
                LlmForwardErrorCodeEnum.FORWARD_FAILED, false, outcome, false);
    }

    /** 仅连接拒绝和 DNS 失败能证明 HTTP 请求未发送；泛化 I/O 超时保持结果未知。 */
    public static UpstreamFailureException transport(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException) {
                return new UpstreamFailureException(LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT.message(),
                        LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT, true, UpstreamExecutionOutcome.NOT_SENT);
            }
        }
        return timeout();
    }
}
