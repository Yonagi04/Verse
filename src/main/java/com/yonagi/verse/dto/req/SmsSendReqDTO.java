package com.yonagi.verse.dto.req;

import com.yonagi.verse.common.messaging.MessageRequestValidation;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 首期每次一个收件人，字段保留阿里云 PhoneNumbers 的语义。 */
public record SmsSendReqDTO(
        /** 调用方稳定的幂等请求标识。 */ String requestId,
        /** 服务端业务场景。 */ String scene,
        /** 可信业务用户，可为空。 */ Long userId,
        /** 可信业务租户，可为空。 */ Long tenantId,
        /** 审核通过的短信签名。 */ String signName,
        /** 单个大陆手机号。 */ String phoneNumbers,
        /** 审核通过的模板代码或模拟模板代码。 */ String templateCode,
        /** 模板变量，业务层不传 SDK JSON 字符串。 */ Map<String, String> templateParams) {
    public SmsSendReqDTO {
        MessageRequestValidation.context(requestId, scene, userId, tenantId);
        MessageRequestValidation.require(MessageRequestValidation.text(signName, 50) && !signName.matches(".*[【】].*"));
        MessageRequestValidation.require(phoneNumbers != null && phoneNumbers.matches("1[3-9][0-9]{9}"));
        MessageRequestValidation.require(templateCode != null && templateCode.matches("[A-Za-z0-9_-]{1,64}"));
        MessageRequestValidation.require(templateParams != null && templateParams.size() <= 20);
        templateParams.forEach((key, value) -> MessageRequestValidation.require(
                key != null && key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                        && MessageRequestValidation.text(value, 256) && !value.contains("${")));
        templateParams = Collections.unmodifiableMap(new TreeMap<>(templateParams));
    }
    @Override public String toString() { return "SmsSendReqDTO[requestId=" + requestId + "]"; }
}
