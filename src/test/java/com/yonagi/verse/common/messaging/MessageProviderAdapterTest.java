package com.yonagi.verse.common.messaging;

import com.yonagi.verse.common.enums.MessageSubmissionStatus;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.messaging.provider.impl.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.aliyun.teaopenapi.models.Config;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MessageProviderAdapterTest {
    private SmsSendReqDTO sms() { return new SmsSendReqDTO("req-1", "TEST", 1L, null, "Verse", "13800138000", "SMS_10001", Map.of("code", "123456")); }
    private EmailSendReqDTO email() { return new EmailSendReqDTO("req-2", "TEST", 1L, null, "test@example.com", "测试主题", "测试正文", null); }

    @Test void smsDevRendersToRawBodyAndNormalizesErrors() {
        var builder = RestClient.builder().baseUrl("http://sms.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var renderer = new SmsTemplateRenderer(new ByteArrayResource("templates:\n  SMS_10001:\n    content: '验证码${code}'\n".getBytes(StandardCharsets.UTF_8)));
        var adapter = new SmsDevAdapter(builder.build(), renderer);
        server.expect(requestTo("http://sms.test/v1/messages"))
                .andExpect(content().json("{\"to\":\"13800138000\",\"from\":\"Verse\",\"body\":\"【Verse】验证码123456\"}"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"msg_123\",\"status\":\"queued\"}"));
        assertEquals(MessageSubmissionStatus.ACCEPTED, adapter.send(sms()).status()); server.verify();
        server.reset();
        server.expect(requestTo("http://sms.test/v1/messages")).andRespond(withBadRequest());
        assertEquals(MessageSubmissionStatus.REJECTED, adapter.send(sms()).status()); server.verify();
        server.reset();
        server.expect(requestTo("http://sms.test/v1/messages")).andRespond(withStatus(org.springframework.http.HttpStatus.REQUEST_TIMEOUT));
        assertEquals(MessageSubmissionStatus.UNKNOWN, adapter.send(sms()).status()); server.verify();
        server.reset();
        server.expect(requestTo("http://sms.test/v1/messages")).andRespond(withServerError());
        assertEquals(MessageSubmissionStatus.UNKNOWN, adapter.send(sms()).status()); server.verify();
        server.reset();
        server.expect(requestTo("http://sms.test/v1/messages")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertEquals(MessageSubmissionStatus.UNKNOWN, adapter.send(sms()).status()); server.verify();
    }

    @Test void officialAliyunSdksMapRequestsAndDoNotRetry() throws Exception {
        var requests = new AtomicInteger();
        var captured = new AtomicReference<String>();
        var action = new AtomicReference<String>();
        var response = new AtomicReference<>("{\"Code\":\"OK\",\"BizId\":\"biz-1\",\"RequestId\":\"request-1\"}");
        var code = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            captured.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            action.set(exchange.getRequestHeaders().getFirst("x-acs-action"));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var config = new Config().setAccessKeyId("fixture-id").setAccessKeySecret("fixture-secret")
                    .setProtocol("http").setEndpoint("127.0.0.1:" + server.getAddress().getPort());
            var sms = new AliyunSmsAdapter(new com.aliyun.dysmsapi20170525.Client(config), 1000, 1000);
            assertEquals(MessageSubmissionStatus.ACCEPTED, sms.send(sms()).status());
            assertTrue(captured.get().contains("SignName=Verse")); assertTrue(captured.get().contains("TemplateCode=SMS_10001"));
            assertTrue(captured.get().contains("123456")); assertTrue(captured.get().contains("OutId=req-1")); assertEquals("SendSms", action.get());
            response.set("{\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\",\"RequestId\":\"request-1\"}");
            assertEquals(MessageSubmissionStatus.REJECTED, sms.send(sms()).status());
            var properties = new MessagingProperties(); properties.getEmail().setAccountName("sender@example.com");
            var email = new AliyunEmailAdapter(new com.aliyun.dm20151123.Client(config), properties);
            response.set("{\"EnvId\":\"env-1\",\"RequestId\":\"request-2\"}");
            assertEquals("env-1", email.send(email()).providerMessageId());
            assertEquals("SingleSendMail", action.get()); assertTrue(captured.get().contains("sender@example.com"));
            response.set("{\"Code\":\"InvalidMailAddress.NotFound\",\"Message\":\"private\",\"RequestId\":\"request-3\"}"); code.set(400);
            assertEquals(MessageSubmissionStatus.REJECTED, email.send(email()).status());
            code.set(500);
            assertEquals(MessageSubmissionStatus.UNKNOWN, email.send(email()).status());
            assertEquals(5, requests.get(), "Non-idempotent SDK calls must not retry");
        } finally { server.stop(0); }
    }
}
