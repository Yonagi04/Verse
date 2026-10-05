package com.yonagi.verse.common.messaging.provider.impl;

import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.common.messaging.provider.SmsProvider;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.tea.TeaException;
import com.aliyun.teautil.models.RuntimeOptions;
import com.alibaba.fastjson2.JSON;

public final class AliyunSmsAdapter implements SmsProvider {
    private final Client client;
    private final int connectTimeout;
    private final int readTimeout;
    public AliyunSmsAdapter(Client client, int connectTimeout, int readTimeout) {
        this.client = client; this.connectTimeout = connectTimeout; this.readTimeout = readTimeout;
    }
    @Override public String name() { return "aliyun"; }
    @Override public MessageSubmissionRespDTO send(SmsSendReqDTO request) {
        var upstream = new SendSmsRequest().setPhoneNumbers(request.phoneNumbers()).setSignName(request.signName())
                .setTemplateCode(request.templateCode()).setTemplateParam(JSON.toJSONString(request.templateParams()))
                .setOutId(request.requestId());
        // OutId 仅是关联标识，不提供服务商幂等保证。
        var runtime = new RuntimeOptions().setAutoretry(false).setMaxAttempts(1)
                .setConnectTimeout(connectTimeout).setReadTimeout(readTimeout);
        try {
            var response = client.sendSmsWithOptions(upstream, runtime);
            if (response == null || response.getBody() == null || response.getBody().getCode() == null) {
                return MessageSubmissionRespDTO.unknown("INVALID_RESPONSE");
            }
            var body = response.getBody();
            if (!"OK".equals(body.getCode())) {
                return new MessageSubmissionRespDTO(MessageSubmissionStatus.REJECTED, body.getRequestId(), body.getBizId(), body.getCode());
            }
            if (body.getBizId() == null || body.getRequestId() == null) return MessageSubmissionRespDTO.unknown("INVALID_RESPONSE");
            return MessageSubmissionRespDTO.accepted(body.getRequestId(), body.getBizId());
        } catch (TeaException e) {
            return AliyunErrors.result(e);
        } catch (Exception e) {
            return ProviderTransportErrors.result(e);
        }
    }
}
