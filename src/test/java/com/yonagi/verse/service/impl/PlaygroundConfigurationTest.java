package com.yonagi.verse.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.CapabilitiesVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ConfigDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.LaneDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RunConfigDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.CreateReqDTO;
import com.yonagi.verse.service.playground.PlaygroundConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaygroundConfigurationTest {
    private static final String LANE_ID = "00000000-0000-0000-0000-000000000001";

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.0", "1e0", "2147483647.0"})
    void exactIntegerRepresentationsKeepPlaygroundLimits(String value) {
        BigDecimal exact = new BigDecimal(value);
        LlmServiceDO model = new LlmServiceDO();
        model.setProviderSettings("{\"playground\":{\"maxTokens\":" + value + "}}");
        CapabilitiesVO caps = PlaygroundConfiguration.capabilities(model, UpstreamProtocol.OPENAI_COMPAT);
        assertEquals(exact.longValueExact(), caps.getMaxTokens());
        assertEquals(exact.longValueExact(), PlaygroundConfiguration.parameters(
                new RunConfigDTO(null, null, null, exact), caps).get("max_tokens"));
    }

    @Test
    void highPrecisionFractionsCannotBecomeTokenCounts() {
        BigDecimal fraction = new BigDecimal("1.00000000000000000001");
        CapabilitiesVO caps = new CapabilitiesVO(true, null, null, 1024L);
        ClientException error = assertThrows(ClientException.class,
                () -> PlaygroundConfiguration.parameters(new RunConfigDTO(null, null, null, fraction), caps));
        assertEquals(PlaygroundErrorCodeEnum.INVALID_CONFIG.code(), error.getErrorCode());
        LlmServiceDO model = new LlmServiceDO();
        model.setProviderSettings("{\"playground\":{\"maxTokens\":1.00000000000000000001}}");
        assertNull(PlaygroundConfiguration.capabilities(model, UpstreamProtocol.OPENAI_COMPAT).getMaxTokens());
    }

    @Test
    void typedHttpConfigurationPreservesTextAndRejectsNumericTitle() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String payload = "{\"config\":{\"lanes\":[{\"laneId\":\"" + LANE_ID
                + "\",\"serviceId\":\"9\",\"config\":{\"system\":\"中文\"}}],\"synced\":true}}";
        CreateReqDTO body = mapper.readValue(payload, CreateReqDTO.class);
        ConfigDTO normalized = PlaygroundConfiguration.normalize(body.getConfig());
        assertEquals("中文", normalized.getLanes().getFirst().getConfig().getSystem());
        assertThrows(ClientException.class, () -> mapper.readValue("{\"title\":42}", CreateReqDTO.class));
    }

    @Test
    void capabilitiesRespectAdapterAndIndependentPlaygroundLimit() {
        LlmServiceDO model = new LlmServiceDO();
        model.setProvider("anthropic");
        model.setMaxOutputTokens(1024L);
        assertNull(PlaygroundConfiguration.capabilities(model, UpstreamProtocol.ANTHROPIC_MESSAGES).getTemperature());
        model.setProviderSettings("{\"playground\":{\"temperature\":{\"min\":0,\"max\":2},\"maxTokens\":8192}}");
        CapabilitiesVO caps = PlaygroundConfiguration.capabilities(model, UpstreamProtocol.ANTHROPIC_MESSAGES);
        assertEquals(1.0D, caps.getTemperature().getMax());
        assertEquals(8192L, caps.getMaxTokens());
        assertEquals(8192L, PlaygroundConfiguration.parameters(Map.of("maxTokens", 8192), caps).get("max_tokens"));
        assertEquals(8192L, PlaygroundConfiguration.parameters(new RunConfigDTO(), caps).get("max_tokens"));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(Map.of("maxTokens", 8193), caps));
        assertThrows(ClientException.class,
                () -> PlaygroundConfiguration.parameters(Map.of("temperature", 1.1D), caps));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(Map.of("maxTokens", 1.5D), caps));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(Map.of("topP", 0.5D), caps));
        assertThrows(ClientException.class,
                () -> PlaygroundConfiguration.parameters(Map.of("maxTokens", "8192"), caps));
    }

    @Test
    void missingPlaygroundLimitDoesNotInheritApiLimit() {
        LlmServiceDO model = new LlmServiceDO();
        model.setMaxOutputTokens(1024L);
        model.setProviderSettings("{\"playground\":\"{\\\"system\\\":true}\"}");
        CapabilitiesVO caps = PlaygroundConfiguration.capabilities(model, UpstreamProtocol.OPENAI_COMPAT);
        assertNull(caps.getMaxTokens());
        assertFalse(PlaygroundConfiguration.parameters(new RunConfigDTO(), caps).containsKey("max_tokens"));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(Map.of("maxTokens", 1), caps));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "01", "abc", "9223372036854775808"})
    void normalizationRejectsInvalidModelIdentifiers(String serviceId) {
        ConfigDTO config = new ConfigDTO(List.of(new LaneDTO(LANE_ID, serviceId, null)), null, null, null);
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.normalize(config));
    }

    @Test
    void normalizationGeneratesLaneWithoutMutatingInputAndRejectsConflictingSyncedConfig() {
        LaneDTO lane = new LaneDTO(null, "9", null);
        ConfigDTO normalized = PlaygroundConfiguration.normalize(new ConfigDTO(List.of(lane), null, null, null));
        String generated = normalized.getLanes().getFirst().getLaneId();
        assertEquals(generated, UUID.fromString(generated).toString());
        assertNull(lane.getLaneId());
        assertEquals(new RunConfigDTO(), normalized.getLanes().getFirst().getConfig());
        LaneDTO other = new LaneDTO(LANE_ID, "9", new RunConfigDTO("独立系统提示", null, null, null));
        ConfigDTO synced = new ConfigDTO(List.of(lane, other), true, null, null);
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.normalize(synced));
    }

    @ParameterizedTest
    @MethodSource("invalidConfigs")
    void normalizationRejectsInvalidLaneTopologyAndOversizeSystem(String scenario, ConfigDTO config) {
        ClientException error = assertThrows(ClientException.class, () -> PlaygroundConfiguration.normalize(config));
        assertEquals(PlaygroundErrorCodeEnum.INVALID_CONFIG.code(), error.getErrorCode(), scenario);
    }

    private static Stream<Arguments> invalidConfigs() {
        LaneDTO lane = new LaneDTO(LANE_ID, "9", new RunConfigDTO());
        return Stream.of(
                Arguments.of("missing config", null),
                Arguments.of("missing lanes", new ConfigDTO(null, false, null, null)),
                Arguments.of("empty lanes", new ConfigDTO(List.of(), false, null, null)),
                Arguments.of("too many lanes", new ConfigDTO(List.of(lane, lane, lane, lane), false, null, null)),
                Arguments.of("null lane", new ConfigDTO(Collections.singletonList(null), false, null, null)),
                Arguments.of("duplicate lane", new ConfigDTO(List.of(lane, lane), false, null, null)),
                Arguments.of("short UUID", new ConfigDTO(List.of(new LaneDTO("short", "9", null)), false, null, null)),
                Arguments.of("oversize system", new ConfigDTO(List.of(new LaneDTO(LANE_ID, "9",
                        new RunConfigDTO("x".repeat(100_001), null, null, null))), false, null, null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"playground\":null}"})
    void unrelatedProviderSettingsDoNotDeclareNumericCapabilities(String settings) {
        LlmServiceDO model = new LlmServiceDO();
        model.setProviderSettings(settings);
        CapabilitiesVO caps = PlaygroundConfiguration.capabilities(model, UpstreamProtocol.OPENAI_COMPAT);
        assertEquals(true, caps.getSystem());
        assertNull(caps.getTemperature());
        assertNull(caps.getMaxTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"min\":\"0\",\"max\":1}", "{\"min\":0,\"max\":\"1\"}",
            "{\"min\":1e1000,\"max\":1}", "{\"min\":0,\"max\":-1e1000}", "{\"min\":1,\"max\":0.5}"})
    void invalidHistoricalRangesAreOmitted(String range) {
        LlmServiceDO model = new LlmServiceDO();
        model.setProviderSettings("{\"playground\":{\"system\":false,\"maxTokens\":\"100\",\"topP\":" + range + "}}");
        CapabilitiesVO caps = PlaygroundConfiguration.capabilities(model, UpstreamProtocol.BEDROCK_CONVERSE);
        assertEquals(false, caps.getSystem());
        assertNull(caps.getTopP());
        assertNull(caps.getMaxTokens());
    }

    @Test
    void disabledSystemAcceptsBlankOrAbsentButNotUserSystemPrompt() {
        CapabilitiesVO caps = new CapabilitiesVO(false, null, null, null);
        assertEquals(Map.of(), PlaygroundConfiguration.parameters(new RunConfigDTO(), caps));
        assertEquals(Map.of(), PlaygroundConfiguration.parameters(Map.of("system", " "), caps));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(Map.of("system", "提示"), caps));
        assertThrows(ClientException.class, () -> PlaygroundConfiguration.parameters(Map.of("system", true), caps));
    }

    @Test
    void numericParametersRejectNonFiniteAndBelowModelRange() {
        CapabilitiesVO caps = new CapabilitiesVO(true,
                new com.yonagi.verse.dto.playground.WorkbenchTypes.RangeVO(0.5D, 1D), null, 100L);
        Map<String, Object> valid = PlaygroundConfiguration.parameters(Map.of("maxTokens", BigDecimal.ONE), caps);
        assertEquals(1L, valid.get("max_tokens"));
        assertThrows(ClientException.class,
                () -> PlaygroundConfiguration.parameters(Map.of("temperature", Double.POSITIVE_INFINITY), caps));
        assertThrows(ClientException.class,
                () -> PlaygroundConfiguration.parameters(new RunConfigDTO(null, new BigDecimal("1e1000"),
                        null, null), caps));
        assertThrows(ClientException.class,
                () -> PlaygroundConfiguration.parameters(Map.of("temperature", 0.25D), caps));
    }
}
