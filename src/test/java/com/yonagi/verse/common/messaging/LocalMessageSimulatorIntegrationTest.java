package com.yonagi.verse.common.messaging;

import com.yonagi.verse.common.enums.MessageSubmissionStatus;

import com.yonagi.verse.common.config.*;
import com.yonagi.verse.common.messaging.provider.impl.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestClient;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LocalMessageSimulatorIntegrationTest {
    @Test @EnabledIfSystemProperty(named = "messaging.sms-dev-it", matches = "true")
    void sendsAndReadsSmsDevMessage() throws Exception {
        String url = System.getProperty("messaging.sms-dev-url", "http://127.0.0.1:4001");
        var p = new MessagingProperties(); p.getSms().setProvider("sms-dev"); p.getSms().setDevBaseUrl(url);
        var adapter = new MessagingConfiguration().smsProvider(p, new StandardEnvironment(), new DefaultResourceLoader());
        var request = new SmsSendReqDTO(UUID.randomUUID().toString(), "TEST", null, null, "Verse", "13800138000", "10001", Map.of("code", "123456"));
        var result = adapter.send(request); assertEquals(MessageSubmissionStatus.ACCEPTED, result.status());
        var stored = RestClient.create(url).get().uri("/v1/messages/" + result.providerMessageId()).retrieve().body(Map.class);
        assertNotNull(stored); assertEquals("【Verse】您正在重置密码，验证码是123456，5分钟内有效，请勿泄露。", stored.get("body"));
    }
    @Test @EnabledIfSystemProperty(named = "messaging.mailpit-it", matches = "true")
    void sendsAndFindsMailpitMessage() throws Exception {
        var p = new MessagingProperties(); p.getEmail().setProvider("mailpit"); p.getEmail().setAccountName("sender@verse.test");
        p.getEmail().setSmtpPort(Integer.getInteger("messaging.mailpit-port", 1025));
        var adapter = new MessagingConfiguration().emailProvider(p, new StandardEnvironment());
        String subject = "Verse-test-" + UUID.randomUUID();
        var result = adapter.send(new EmailSendReqDTO(UUID.randomUUID().toString(), "TEST", null, null, "user@verse.test", subject, "测试内容", null));
        assertEquals(MessageSubmissionStatus.ACCEPTED, result.status());
        String messages = RestClient.create(System.getProperty("messaging.mailpit-url", "http://127.0.0.1:8025"))
                .get().uri("/api/v1/messages").retrieve().body(String.class);
        assertNotNull(messages); assertTrue(messages.contains(subject));
    }
}
