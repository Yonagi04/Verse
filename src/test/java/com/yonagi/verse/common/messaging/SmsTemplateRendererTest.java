package com.yonagi.verse.common.messaging;

import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.common.messaging.SmsTemplateRenderer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SmsTemplateRendererTest {
    private SmsTemplateRenderer renderer(String yaml) {
        return new SmsTemplateRenderer(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void rendersEquivalentSignatureAndVariables() {
        var renderer = renderer("templates:\n  '10001':\n    content: '您的验证码是${code}，5分钟内有效。'\n");
        assertEquals("【Verse】您的验证码是123456，5分钟内有效。",
                renderer.render("Verse", "10001", Map.of("code", "123456")));
    }

    @Test void rejectsMissingExtraUnknownAndRecursiveVariables() {
        var renderer = renderer("templates:\n  '10001':\n    content: '验证码${code}'\n");
        assertThrows(MessageSendException.class, () -> renderer.render("Verse", "10001", Map.of()));
        assertThrows(MessageSendException.class, () -> renderer.render("Verse", "10001", Map.of("code", "1", "other", "2")));
        assertThrows(MessageSendException.class, () -> renderer.render("Verse", "unknown", Map.of("code", "1")));
        assertThrows(MessageSendException.class, () -> renderer.render("Verse", "10001", Map.of("code", "${other}")));
    }

    @Test void failsStartupOnDuplicateKeysMalformedPlaceholdersAndEmbeddedSignature() {
        assertThrows(IllegalStateException.class, () -> renderer("templates:\n  '1': {content: a}\n  '1': {content: b}\n"));
        assertThrows(IllegalStateException.class, () -> renderer("templates:\n  '1': {content: 'code${bad-name}'}\n"));
        assertThrows(IllegalStateException.class, () -> renderer("templates:\n  '1': {content: '【Verse】验证码${code}'}\n"));
        assertThrows(IllegalStateException.class, () -> renderer("templates: {}"));
    }
}
