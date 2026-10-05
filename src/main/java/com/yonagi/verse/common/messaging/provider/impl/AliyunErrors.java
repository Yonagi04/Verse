package com.yonagi.verse.common.messaging.provider.impl;

import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;

import com.aliyun.tea.TeaException;

/** 两个阿里云产品共享 SDK 错误结构，但业务拒绝与网络不确定性必须区分。 */
final class AliyunErrors {
    private AliyunErrors() { }
    static MessageSubmissionRespDTO result(TeaException error) {
        var transport = ProviderTransportErrors.result(error);
        if (transport.retryable()) return transport;
        Object status = error.getData() == null ? null : error.getData().get("statusCode");
        if (status instanceof Number number && number.intValue() >= 400 && number.intValue() < 500 && number.intValue() != 408) {
            return MessageSubmissionRespDTO.rejected(error.getCode());
        }
        return MessageSubmissionRespDTO.unknown("UPSTREAM_ERROR");
    }
}
