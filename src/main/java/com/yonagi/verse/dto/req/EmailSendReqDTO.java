package com.yonagi.verse.dto.req;

import com.yonagi.verse.common.messaging.MessageRequestValidation;

public record EmailSendReqDTO(
        /** 调用方稳定的幂等请求标识。 */ String requestId,
        /** 服务端业务场景。 */ String scene,
        /** 可信业务用户，可为空。 */ Long userId,
        /** 可信业务租户，可为空。 */ Long tenantId,
        /** 单个收件邮箱。 */ String toAddress,
        /** 邮件主题。 */ String subject,
        /** 文本正文，可为空。 */ String textBody,
        /** HTML 正文，可为空。 */ String htmlBody) {
    public EmailSendReqDTO {
        MessageRequestValidation.context(requestId, scene, userId, tenantId);
        MessageRequestValidation.require(MessageRequestValidation.email(toAddress));
        MessageRequestValidation.require(MessageRequestValidation.text(subject, 256));
        MessageRequestValidation.require((textBody != null && !textBody.isBlank()) || (htmlBody != null && !htmlBody.isBlank()));
        MessageRequestValidation.body(textBody);
        MessageRequestValidation.body(htmlBody);
    }
    @Override public String toString() { return "EmailSendReqDTO[requestId=" + requestId + "]"; }
}
