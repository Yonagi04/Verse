package com.yonagi.verse.dto.req;

/** 仅用于加密持久化的内部任务，不作为 HTTP 输入；身份在提交时固定。 */
public record MessageDeliveryTaskDTO(
        /** 短信请求，与邮件请求互斥。 */ SmsSendReqDTO sms,
        /** 邮件请求，与短信请求互斥。 */ EmailSendReqDTO email,
        /** 验证码预留信息，普通消息为空。 */ VerificationState verification) {
    public MessageDeliveryTaskDTO {
        if ((sms == null) == (email == null) || (email != null && verification != null)) {
            throw new IllegalArgumentException("Invalid delivery task");
        }
    }
    @Override public String toString() { return "MessageDeliveryTaskDTO[redacted]"; }

    public record VerificationState(
            /** 验证码键，包含用户 ID 或手机号哈希。 */ String codeKey,
            /** 本次冷却键。 */ String rateKey,
            /** 请求专属旧状态快照键。 */ String backupKey,
            /** 当前请求所有权键。 */ String ownerKey) { }
}
