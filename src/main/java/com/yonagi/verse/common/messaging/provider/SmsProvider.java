package com.yonagi.verse.common.messaging.provider;

import com.yonagi.verse.dto.req.SmsSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;

public interface SmsProvider {
    String name();
    /** 纯校验必须在产生发送记录和网络副作用前完成。 */
    default void validate(SmsSendReqDTO request) { }
    MessageSubmissionRespDTO send(SmsSendReqDTO request);
}
