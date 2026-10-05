package com.yonagi.verse.common.messaging;

import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.common.messaging.provider.impl.*;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class ProviderTransportErrorsTest {
    @Test void onlyConnectionEstablishmentFailureIsRetryable() {
        assertTrue(ProviderTransportErrors.result(new RuntimeException(new ConnectException())).retryable());
        assertTrue(ProviderTransportErrors.result(new UnknownHostException()).retryable());
        var smtp = new MessagingException("connection", new ConnectException());
        assertTrue(ProviderTransportErrors.result(smtp).retryable());
        var timeout = ProviderTransportErrors.result(new SocketTimeoutException());
        assertEquals(MessageSubmissionStatus.UNKNOWN, timeout.status()); assertFalse(timeout.retryable());
        assertFalse(ProviderTransportErrors.result(new SocketException("Connection reset")).retryable());
    }
    @Test void smsDevAdapterPreservesSafeRetryBoundary() {
        var builder = RestClient.builder().baseUrl("http://sms.test"); var server = MockRestServiceServer.bindTo(builder).build();
        var renderer = new SmsTemplateRenderer(new ByteArrayResource("templates:\n  '10001':\n    content: '验证码${code}'\n".getBytes(StandardCharsets.UTF_8)));
        var adapter = new SmsDevAdapter(builder.build(), renderer);
        var request = new SmsSendReqDTO("r", "TEST", null, null, "Verse", "13800138000", "10001", Map.of("code", "123456"));
        server.expect(requestTo("http://sms.test/v1/messages")).andRespond(withException(new ConnectException()));
        assertTrue(adapter.send(request).retryable()); server.verify(); server.reset();
        server.expect(requestTo("http://sms.test/v1/messages")).andRespond(withException(new SocketTimeoutException()));
        var result = adapter.send(request); assertEquals(MessageSubmissionStatus.UNKNOWN, result.status()); assertFalse(result.retryable()); server.verify();
    }
}
