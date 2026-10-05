package com.yonagi.verse.service.messaging;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.common.messaging.provider.EmailProvider;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class EmailSendService {
    private final EmailProvider provider;
    private final SendRecordStore records;
    private final MessagingProperties properties;
    private final MeterRegistry metrics;
    public EmailSendService(EmailProvider provider, SendRecordStore records, MeterRegistry metrics, MessagingProperties properties) {
        this.provider = provider; this.records = records;
        this.properties = properties;
        this.metrics = metrics;
    }
    public MessageSubmissionRespDTO send(EmailSendReqDTO request) {
        provider.validate(request);
        var result = records.enqueue(request, provider.name(), properties.getAsync());
        metrics.counter("verse.messaging.requests", "channel", "email", "status", result.status().name()).increment();
        return result;
    }
}
