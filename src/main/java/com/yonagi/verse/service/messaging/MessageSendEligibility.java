package com.yonagi.verse.service.messaging;

import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;

/** 场景领域在真实外发前复核资格；null 表示允许，拒绝可明确取消或有界等待。 */
public interface MessageSendEligibility {
    MessageSubmissionRespDTO verify(String scene, String requestId);
}
