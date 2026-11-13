package com.yonagi.verse.common.messaging;

import com.yonagi.verse.common.convention.exception.MessageSendException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailTemplateRendererTest {
    private static final String TEMPLATE = "templates:\n  NOTICE:\n    subject: '【Verse】${name}'\n"
            + "    content: |-\n      您好${name}\n\n      ${url}";

    @Test
    void rendersLiteralUnicodeDollarsBackslashesAndBlankLines() {
        EmailTemplateRenderer.RenderedEmail result = renderer(TEMPLATE).render("NOTICE",
                Map.of("name", "租户$1\\", "url", "http://reconciliation.localhost:3000/AbCd1234"));
        assertEquals("【Verse】租户$1\\", result.subject());
        assertEquals("您好租户$1\\\n\nhttp://reconciliation.localhost:3000/AbCd1234", result.content());
    }

    @Test
    void bundledReconciliationTemplatePreservesExistingTextExactly() {
        EmailTemplateRenderer renderer = new EmailTemplateRenderer(
                new ClassPathResource("messaging/email/templates.yml"));
        EmailTemplateRenderer.RenderedEmail result = renderer.render("COST_RECONCILIATION",
                Map.of("tenantName", "测试租户", "pendingCount", "2",
                        "reconciliationUrl", "http://reconciliation.localhost:3000/AbCd1234"));
        assertEquals("【Verse】测试租户有模型用量待补账", result.subject());
        assertEquals("管理员您好！\n\nVerse 在恢复后的核对中发现，租户「测试租户」本批次有 "
                + "2 笔模型调用费用仍待核对。请打开自助补账页面并登录：\n"
                + "http://reconciliation.localhost:3000/AbCd1234\n\n页面仅展示本次扫描的待补账和已处理记录。"
                + "待补账期间，模型调用按已确认费用判断限额。", result.content());
    }

    @Test
    void rejectsUnknownMissingExtraNullAndOversizedParameters() {
        EmailTemplateRenderer renderer = renderer(TEMPLATE);
        Map<String, String> params = Map.of("name", "租户", "url", "https://example.com");
        assertThrows(MessageSendException.class, () -> renderer.render("MISSING", params));
        assertThrows(MessageSendException.class, () -> renderer.render(null, params));
        assertThrows(MessageSendException.class, () -> renderer.render("NOTICE", null));
        assertThrows(MessageSendException.class, () -> renderer.render("NOTICE", Map.of("name", "租户")));
        assertThrows(MessageSendException.class, () -> renderer.render("NOTICE",
                Map.of("name", "租户", "url", "https://example.com", "extra", "unexpected")));
        Map<String, String> nullValue = new HashMap<>(params);
        nullValue.put("name", null);
        assertThrows(MessageSendException.class, () -> renderer.render("NOTICE", nullValue));
        assertThrows(MessageSendException.class, () -> renderer.render("NOTICE",
                Map.of("name", "a".repeat(257), "url", "https://example.com")));
        assertThrows(MessageSendException.class, () -> renderer.render("NOTICE",
                Map.of("name", "租户", "url", "a".repeat(2049))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "new\nheader", "new\rheader", "${other}"})
    void rejectsEmptyRecursiveAndHeaderInjectionValues(String value) {
        assertThrows(MessageSendException.class, () -> renderer(TEMPLATE).render("NOTICE",
                Map.of("name", value, "url", "https://example.com")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "[]", "other: {}", "templates: {}", "templates: []",
            "templates: {}\nother: ignored", "templates: {1: {subject: s, content: c}}",
            "templates: {'bad-code!': {subject: s, content: c}}", "templates: {NOTICE: []}",
            "templates: {NOTICE: {subject: s}}", "templates: {NOTICE: {subject: s, content: c, extra: v}}",
            "templates: {NOTICE: {subject: 1, content: c}}", "templates: {NOTICE: {subject: '', content: c}}",
            "templates: {NOTICE: {subject: s, content: 1}}", "templates: {NOTICE: {subject: s, content: ''}}",
            "templates: {NOTICE: {subject: 'bad${x-y}', content: c}}",
            "templates: {NOTICE: {subject: s, content: '${bad-name}'}}",
            "templates: {NOTICE: {subject: s, content: c, content: duplicate}}",
            "templates:\n  NOTICE: {subject: s, content: c}\n  NOTICE: {subject: s, content: duplicate}",
            "templates:\n  NOTICE: &entry {subject: s, content: c}\n  OTHER: *entry"})
    void failsStartupForInvalidOrAmbiguousYaml(String yaml) {
        assertThrows(IllegalStateException.class, () -> renderer(yaml == null ? "null" : yaml));
    }

    @Test
    void acceptsLiteralTemplatesAndRejectsInvalidHeadersOrExcessiveResources() {
        assertEquals(new EmailTemplateRenderer.RenderedEmail("标题", "正文\n第二行"),
                renderer("templates:\n  NOTICE:\n    subject: 标题\n    content: |-\n      正文\n      第二行")
                        .render("NOTICE", Map.of()));
        assertThrows(IllegalStateException.class,
                () -> renderer("templates: {NOTICE: {subject: \"bad\\nheader\", content: c}}"));
        assertThrows(IllegalStateException.class,
                () -> renderer("templates: {NOTICE: {subject: \"bad\\rheader\", content: c}}"));
        assertThrows(IllegalStateException.class,
                () -> renderer("templates: {NOTICE: {subject: '" + "s".repeat(257) + "', content: c}}"));
        assertThrows(IllegalStateException.class,
                () -> renderer("templates: {NOTICE: {subject: s, content: '" + "c".repeat(262145) + "'}}"));
        assertThrows(IllegalStateException.class,
                () -> renderer("templates: {NOTICE: {subject: s, content: '" + "文".repeat(180000) + "'}}"));
        assertThrows(IllegalStateException.class, () -> new EmailTemplateRenderer(
                new ClassPathResource("messaging/email/missing.yml")));
        EmailTemplateRenderer expanding = renderer("templates: {NOTICE: {subject: s, content: '"
                + "${value}".repeat(300) + "'}}");
        assertThrows(MessageSendException.class,
                () -> expanding.render("NOTICE", Map.of("value", "c".repeat(1800))));
    }

    private EmailTemplateRenderer renderer(String yaml) {
        return new EmailTemplateRenderer(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
