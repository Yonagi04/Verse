package com.yonagi.verse.common.messaging.provider.impl;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.common.messaging.provider.EmailProvider;

import com.aliyun.dm20151123.Client;
import com.aliyun.dm20151123.models.SingleSendMailRequest;
import com.aliyun.tea.TeaException;
import com.aliyun.teautil.models.RuntimeOptions;

public final class AliyunEmailAdapter implements EmailProvider {
    private final Client client;
    private final MessagingProperties properties;
    public AliyunEmailAdapter(Client client, MessagingProperties properties) {
        this.client = client; this.properties = properties;
    }
    @Override public String name() { return "aliyun"; }
    @Override public MessageSubmissionRespDTO send(EmailSendReqDTO request) {
        var email = properties.getEmail();
        var upstream = new SingleSendMailRequest().setAccountName(email.getAccountName()).setAddressType(1)
                .setReplyToAddress(email.isReplyToAddress()).setFromAlias(email.getFromAlias())
                .setToAddress(request.toAddress()).setSubject(request.subject())
                .setTextBody(request.textBody()).setHtmlBody(request.htmlBody());
        var runtime = new RuntimeOptions().setAutoretry(false).setMaxAttempts(1)
                .setConnectTimeout((int) properties.getConnectTimeout().toMillis())
                .setReadTimeout((int) properties.getReadTimeout().toMillis());
        try {
            var response = client.singleSendMailWithOptions(upstream, runtime);
            if (response == null || response.getBody() == null || response.getBody().getEnvId() == null
                    || response.getBody().getRequestId() == null) return MessageSubmissionRespDTO.unknown("INVALID_RESPONSE");
            return MessageSubmissionRespDTO.accepted(response.getBody().getRequestId(), response.getBody().getEnvId());
        } catch (TeaException e) {
            return AliyunErrors.result(e);
        } catch (Exception e) {
            return ProviderTransportErrors.result(e);
        }
    }
}
