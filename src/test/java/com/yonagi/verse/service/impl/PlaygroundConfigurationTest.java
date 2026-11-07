package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class PlaygroundConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"1", "1.0", "1e0", "2147483647.0"})
    void exactIntegerRepresentationsKeepVersionsAndPlaygroundLimits(String value) {
        BigDecimal exact = new BigDecimal(value);
        JSONObject versions = JSONObject.of("version", exact, "revision", exact);
        PlaygroundConfiguration.fields(versions, "version", "revision");
        assertEquals(exact.intValueExact(), versions.getIntValue("version"));
        LlmServiceDO model = new LlmServiceDO();
        model.setProviderSettings("{\"playground\":{\"maxTokens\":" + value + "}}");
        JSONObject caps = PlaygroundConfiguration.capabilities(model, UpstreamProtocol.OPENAI_COMPAT);
        assertEquals(exact.longValueExact(), caps.getLongValue("maxTokens"));
        assertEquals(exact.longValueExact(), PlaygroundConfiguration.parameters(
                JSONObject.of("maxTokens", exact), caps).getLongValue("max_tokens"));
    }

    @Test
    void highPrecisionFractionsCannotBecomeVersionsOrTokenCounts() {
        BigDecimal fraction = new BigDecimal("1.00000000000000000001");
        for (String field : java.util.List.of("version", "revision")) {
            assertThrows(ClientException.class,
                    () -> PlaygroundConfiguration.fields(JSONObject.of(field, fraction), field));
        }
        JSONObject caps = JSONObject.of("system", true, "maxTokens", 1024);
        assertThrows(ClientException.class,
                () -> PlaygroundConfiguration.parameters(JSONObject.of("maxTokens", fraction), caps));
        LlmServiceDO model = new LlmServiceDO();
        model.setProviderSettings("{\"playground\":{\"maxTokens\":1.00000000000000000001}}");
        assertFalse(PlaygroundConfiguration.capabilities(model, UpstreamProtocol.OPENAI_COMPAT)
                .containsKey("maxTokens"));
    }

    @Test void jacksonHttpMapsNormalizeLikeInternalJson() throws Exception {
        JSONObject body = new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"lanes\":[{\"laneId\":\"00000000-0000-0000-0000-000000000001\",\"serviceId\":\"9\",\"config\":{\"system\":\"中文\"}}],\"synced\":true}", JSONObject.class);
        JSONObject normalized = PlaygroundConfiguration.normalize(body);
        assertEquals("中文", normalized.getJSONArray("lanes").getJSONObject(0).getJSONObject("config").getString("system"));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.fields(JSONObject.of("title", 42), "title"));
    }
    @Test void capabilitiesRespectAdapterAndIndependentPlaygroundLimit() {
        LlmServiceDO model = new LlmServiceDO(); model.setProvider("anthropic"); model.setMaxOutputTokens(1024L);
        assertFalse(PlaygroundConfiguration.capabilities(model, UpstreamProtocol.ANTHROPIC_MESSAGES).containsKey("temperature"));
        model.setProviderSettings("{\"playground\":{\"temperature\":{\"min\":0,\"max\":2},\"maxTokens\":8192}}");
        JSONObject caps = PlaygroundConfiguration.capabilities(model, UpstreamProtocol.ANTHROPIC_MESSAGES);
        assertEquals(1.0, caps.getJSONObject("temperature").getDoubleValue("max")); assertEquals(8192L, caps.getLongValue("maxTokens"));
        assertEquals(8192L, PlaygroundConfiguration.parameters(JSONObject.of("maxTokens", 8192), caps).getLongValue("max_tokens"));
        assertEquals(8192L, PlaygroundConfiguration.parameters(new JSONObject(), caps).getLongValue("max_tokens"));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(JSONObject.of("maxTokens", 8193), caps));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(JSONObject.of("temperature", 1.1), caps));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(JSONObject.of("maxTokens", 1.5), caps));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(JSONObject.of("topP", 0.5), caps));
    }
    @Test void missingPlaygroundLimitDoesNotInheritApiLimit() {
        LlmServiceDO model = new LlmServiceDO(); model.setMaxOutputTokens(1024L);
        model.setProviderSettings("{\"playground\":\"{\\\"system\\\":true}\"}");
        JSONObject caps = PlaygroundConfiguration.capabilities(model, UpstreamProtocol.OPENAI_COMPAT);
        assertFalse(caps.containsKey("maxTokens"));
        assertFalse(PlaygroundConfiguration.parameters(new JSONObject(), caps).containsKey("max_tokens"));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(JSONObject.of("maxTokens", 1), caps));
    }
}
