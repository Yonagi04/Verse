package com.yonagi.verse.service.playground;

import com.fasterxml.jackson.databind.JsonNode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.common.util.JsonNumbers;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.CapabilitiesVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ConfigDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.LaneDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RangeVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RunConfigDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 工作台配置规范化及模型级参数范围规则。 */
public final class PlaygroundConfiguration {
    private static final Pattern SERVICE_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private PlaygroundConfiguration() {
    }

    /** 生成独立规范化配置；调用方栏位不会被生成标识或默认值原地改写。 */
    public static ConfigDTO normalize(ConfigDTO input) {
        if (input == null || input.getLanes() == null || input.getLanes().isEmpty() || input.getLanes().size() > 3) {
            throw invalid();
        }
        Set<String> ids = new HashSet<>();
        List<LaneDTO> lanes = new ArrayList<>();
        for (LaneDTO lane : input.getLanes()) {
            if (lane == null) {
                throw invalid();
            }
            String id = normalizedLaneId(lane.getLaneId());
            String serviceId = lane.getServiceId();
            if (!ids.add(id) || serviceId == null || !SERVICE_ID.matcher(serviceId).matches()) {
                throw invalid();
            }
            try {
                Long.parseLong(serviceId);
            } catch (NumberFormatException ex) {
                throw invalid();
            }
            RunConfigDTO run = lane.getConfig();
            if (run != null && run.getSystem() != null && run.getSystem().length() > 100_000) {
                throw invalid();
            }
            RunConfigDTO copy = run == null ? new RunConfigDTO()
                    : new RunConfigDTO(run.getSystem(), run.getTemperature(), run.getTopP(), run.getMaxTokens());
            lanes.add(new LaneDTO(id, serviceId, copy));
        }
        boolean synced = !Boolean.FALSE.equals(input.getSynced());
        if (synced && lanes.stream().anyMatch(lane -> !lanes.getFirst().getConfig().equals(lane.getConfig()))) {
            throw invalid();
        }
        return new ConfigDTO(lanes, synced, input.getPresetId(), input.getPresetVersion());
    }

    private static String normalizedLaneId(String id) {
        if (id == null) {
            return UUID.randomUUID().toString();
        }
        if (id.length() != 36) {
            throw invalid();
        }
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    /** 提取显式能力；非法历史 Token 上限不发布，工作台上限独立于普通 API。 */
    public static CapabilitiesVO capabilities(LlmServiceDO service, UpstreamProtocol protocol) {
        CapabilitiesVO caps = new CapabilitiesVO(true, null, null, null);
        if (service.getProviderSettings() == null) {
            return caps;
        }
        JsonNode explicit = WorkbenchJson.tree(service.getProviderSettings()).get("playground");
        if (explicit == null || explicit.isNull()) {
            return caps;
        }
        if (explicit.isTextual()) {
            explicit = WorkbenchJson.tree(explicit.textValue());
        }
        caps.setSystem(!explicit.path("system").isBoolean() || explicit.path("system").booleanValue());
        double adapterMax = protocol == UpstreamProtocol.ANTHROPIC_MESSAGES
                || protocol == UpstreamProtocol.BEDROCK_CONVERSE ? 1 : 2;
        caps.setTemperature(range(explicit.get("temperature"), adapterMax));
        caps.setTopP(range(explicit.get("topP"), 1));
        JsonNode max = explicit.get("maxTokens");
        if (max != null && max.isNumber() && JsonNumbers.isPositiveInteger(max.decimalValue(), Integer.MAX_VALUE)) {
            caps.setMaxTokens(max.longValue());
        }
        return caps;
    }

    private static RangeVO range(JsonNode node, double adapterMax) {
        if (node == null || !node.path("min").isNumber() || !node.path("max").isNumber()) {
            return null;
        }
        double min = Math.max(0, node.get("min").doubleValue());
        double max = Math.min(adapterMax, node.get("max").doubleValue());
        return Double.isFinite(min) && Double.isFinite(max) && min <= max ? new RangeVO(min, max) : null;
    }

    /** 适配内部转发参数，业务判断由同一工作台规则完成。 */
    public static Map<String, Object> parameters(Map<String, ?> config, CapabilitiesVO caps) {
        Object system = config.get("system");
        if (system != null && !(system instanceof String)) {
            throw invalid();
        }
        return parameters(new RunConfigDTO((String) system, decimal(config.get("temperature")),
                decimal(config.get("topP")), decimal(config.get("maxTokens"))), caps);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw invalid();
        }
        return value instanceof BigDecimal exact ? exact : new BigDecimal(value.toString());
    }

    /** 生成内部上游参数；Token 必须是能力上界内的精确正整数。 */
    public static Map<String, Object> parameters(RunConfigDTO config, CapabilitiesVO caps) {
        Map<String, Object> parameters = new HashMap<>();
        if (!Boolean.TRUE.equals(caps.getSystem()) && config.getSystem() != null && !config.getSystem().isBlank()) {
            throw invalid();
        }
        parameter(parameters, "temperature", config.getTemperature(), caps.getTemperature());
        parameter(parameters, "top_p", config.getTopP(), caps.getTopP());
        if (config.getMaxTokens() != null) {
            Long max = caps.getMaxTokens();
            if (max == null || !JsonNumbers.isPositiveInteger(config.getMaxTokens(), max)) {
                throw invalid();
            }
            parameters.put("max_tokens", config.getMaxTokens().longValue());
        } else if (caps.getMaxTokens() != null) {
            parameters.put("max_tokens", caps.getMaxTokens());
        }
        return parameters;
    }

    private static void parameter(Map<String, Object> result, String name, BigDecimal value, RangeVO range) {
        if (value == null) {
            return;
        }
        double candidate = value.doubleValue();
        if (range == null || !Double.isFinite(candidate) || candidate < range.getMin() || candidate > range.getMax()) {
            throw invalid();
        }
        result.put(name, candidate);
    }

    /** 统一安全配置错误，不暴露解析细节和用户正文。 */
    public static ClientException invalid() {
        return new ClientException(PlaygroundErrorCodeEnum.INVALID_CONFIG);
    }
}
