package com.yonagi.verse.dto.resp;

import com.yonagi.verse.common.enums.MessageSubmissionStatus;

/** QUEUED 为本地持久化受理；ACCEPTED 为服务商受理，均不代表最终送达。 */
public record MessageSubmissionRespDTO(
        /** 受理状态。 */ MessageSubmissionStatus status,
        /** 服务商请求标识。 */ String providerRequestId,
        /** 服务商消息/回执标识。 */ String providerMessageId,
        /** 脱敏后的协议错误码，不含上游错误正文。 */ String errorCode,
        /** 已证明未外发的临时失败，允许后台有界重试。 */ boolean retryable) {

    public MessageSubmissionRespDTO(MessageSubmissionStatus status, String providerRequestId, String providerMessageId, String errorCode) {
        this(status, providerRequestId, providerMessageId, errorCode, false);
    }

    public MessageSubmissionRespDTO {
        if (status == null || status == MessageSubmissionStatus.SUBMITTING) throw new IllegalArgumentException("Invalid terminal status");
        if (retryable && status != MessageSubmissionStatus.REJECTED) throw new IllegalArgumentException("Unsafe retry state");
        providerRequestId = safe(providerRequestId);
        providerMessageId = safe(providerMessageId);
        errorCode = safe(errorCode);
    }

    private static String safe(String value) {
        return value != null && value.matches("[A-Za-z0-9_.@:-]{1,128}") ? value : null;
    }
    public static MessageSubmissionRespDTO accepted(String requestId, String messageId) {
        return new MessageSubmissionRespDTO(MessageSubmissionStatus.ACCEPTED, requestId, messageId, null);
    }
    public static MessageSubmissionRespDTO rejected(String code) {
        return new MessageSubmissionRespDTO(MessageSubmissionStatus.REJECTED, null, null, code);
    }
    public static MessageSubmissionRespDTO unknown(String code) {
        return new MessageSubmissionRespDTO(MessageSubmissionStatus.UNKNOWN, null, null, code);
    }
    public static MessageSubmissionRespDTO queued() {
        return new MessageSubmissionRespDTO(MessageSubmissionStatus.QUEUED, null, null, null);
    }
    public static MessageSubmissionRespDTO retryable(String code) {
        return new MessageSubmissionRespDTO(MessageSubmissionStatus.REJECTED, null, null, code, true);
    }
    public static MessageSubmissionRespDTO expired(String code) {
        return new MessageSubmissionRespDTO(MessageSubmissionStatus.EXPIRED, null, null, code);
    }
}
