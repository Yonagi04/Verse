package com.yonagi.verse.service.messaging;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.common.messaging.provider.SmsProvider;
import com.yonagi.verse.common.messaging.MessageRequestValidation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class SmsSendService {
    private final SmsProvider provider;
    private final SendRecordStore records;
    private final MessagingProperties properties;
    private final MeterRegistry metrics;
    public SmsSendService(SmsProvider provider, SendRecordStore records, MeterRegistry metrics, MessagingProperties properties) {
        this.provider = provider; this.records = records;
        this.properties = properties;
        this.metrics = metrics;
    }
    public MessageSubmissionRespDTO send(SmsSendReqDTO request) {
        return send(request, null);
    }
    /** 验证码恢复信息随任务加密保存，不能依赖请求线程回调。 */
    public MessageSubmissionRespDTO send(SmsSendReqDTO request, VerificationCodeStore.Reservation verification) {
        provider.validate(request);
        if (verification != null) MessageRequestValidation.require(request.requestId().equals(verification.requestId())
                && verification.code() != null && verification.code().equals(request.templateParams().get("code")));
        var result = records.enqueue(request, provider.name(), verification, properties.getAsync());
        metrics.counter("verse.messaging.requests", "channel", "sms", "status", result.status().name()).increment();
        return result;
    }
}
