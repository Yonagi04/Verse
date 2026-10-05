package com.yonagi.verse.common.messaging.provider;

import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;

public interface EmailProvider {
    String name();
    default void validate(EmailSendReqDTO request) { }
    MessageSubmissionRespDTO send(EmailSendReqDTO request);
}
