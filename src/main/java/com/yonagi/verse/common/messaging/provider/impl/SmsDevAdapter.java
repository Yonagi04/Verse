package com.yonagi.verse.common.messaging.provider.impl;

import com.yonagi.verse.common.messaging.SmsTemplateRenderer;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.common.messaging.provider.SmsProvider;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.MediaType;
import java.util.Map;

public final class SmsDevAdapter implements SmsProvider {
    private final RestClient client;
    private final SmsTemplateRenderer renderer;
    public SmsDevAdapter(RestClient client, SmsTemplateRenderer renderer) {
        this.client = client; this.renderer = renderer;
    }
    @Override public String name() { return "sms-dev"; }
    @Override public void validate(SmsSendReqDTO request) {
        renderer.render(request.signName(), request.templateCode(), request.templateParams());
    }
    @Override public MessageSubmissionRespDTO send(SmsSendReqDTO request) {
        String body = renderer.render(request.signName(), request.templateCode(), request.templateParams());
        try {
            var response = client.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("to", request.phoneNumbers(), "from", request.signName(), "body", body))
                    .retrieve().toEntity(Response.class);
            Response result = response.getBody();
            if (response.getStatusCode().value() != 201 || result == null || result.id() == null
                    || !result.id().matches("msg_[A-Za-z0-9_-]{1,100}") || !"queued".equals(result.status())) {
                return MessageSubmissionRespDTO.unknown("INVALID_RESPONSE");
            }
            return MessageSubmissionRespDTO.accepted(null, result.id());
        } catch (RestClientResponseException e) {
            return e.getStatusCode().is4xxClientError() && e.getStatusCode().value() != 408 ? MessageSubmissionRespDTO.rejected("HTTP_" + e.getStatusCode().value())
                    : MessageSubmissionRespDTO.unknown("HTTP_" + e.getStatusCode().value());
        } catch (RuntimeException e) {
            return ProviderTransportErrors.result(e);
        }
    }
    private record Response(String id, String status) { }
}
