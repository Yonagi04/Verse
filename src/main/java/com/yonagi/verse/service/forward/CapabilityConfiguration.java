package com.yonagi.verse.service.forward;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CredentialMode;
import com.yonagi.verse.common.enums.LlmManageErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.common.util.JsonNumbers;
import com.yonagi.verse.dto.req.CapabilityBindingReqDTO;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 服务配置校验，禁止供应商名称隐式决定协议。 */
public final class CapabilityConfiguration {
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final double MAX_TEMPERATURE = 2D;
    private static final double MAX_TOP_P = 1D;

    private CapabilityConfiguration() {}

    public static List<CapabilityBindingReqDTO> normalize(List<CapabilityBindingReqDTO> bindings) {
        if (bindings == null) {
            CapabilityBindingReqDTO legacy = new CapabilityBindingReqDTO();
            legacy.setOperation(ModelOperation.CHAT_COMPLETIONS.name());
            legacy.setUpstreamProtocol(UpstreamProtocol.OPENAI_COMPAT.name());
            legacy.setEnabled(true);
            return List.of(legacy);
        }
        if (bindings.isEmpty()) throw invalid();
        Set<ModelOperation> seen = EnumSet.noneOf(ModelOperation.class);
        List<CapabilityBindingReqDTO> result = new ArrayList<>();
        for (CapabilityBindingReqDTO binding : bindings) {
            if (binding == null) throw invalid();
            try {
                ModelOperation operation = ModelOperation.valueOf(binding.getOperation());
                UpstreamProtocol.valueOf(binding.getUpstreamProtocol());
                if (!seen.add(operation)) throw invalid();
            } catch (IllegalArgumentException | NullPointerException e) {
                throw invalid();
            }
            result.add(binding);
        }
        return result;
    }

    public static CredentialMode validate(String provider, String mode, String apiUrl, String apiKey,
                                          Map<String, String> settings, List<CapabilityBindingReqDTO> bindings) {
        if (!StringUtils.hasText(provider)) throw invalid();
        CredentialMode credential;
        try {
            credential = mode == null ? CredentialMode.API_KEY : CredentialMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        Map<String, String> options = settings == null ? Map.of() : settings;
        if (!Set.of("deployment", "apiVersion", "region", "playground").containsAll(options.keySet())) throw invalid();
        // 细分模型能力以 JSON 字符串保存，保持已有 providerSettings 的字符串值契约。
        if (options.containsKey("playground")) validatePlayground(options.get("playground"));
        if (StringUtils.hasText(apiUrl)) {
            try {
                java.net.URI uri = java.net.URI.create(apiUrl);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || !StringUtils.hasText(uri.getHost()) || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null) throw invalid();
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
        }
        if (options.containsKey("region") && (options.get("region") == null
                || !options.get("region").matches("[a-z]{2}-[a-z]+-\\d"))) throw invalid();
        if (options.containsKey("deployment") && (options.get("deployment") == null
                || !options.get("deployment").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) throw invalid();
        if (options.containsKey("apiVersion") && (options.get("apiVersion") == null
                || !options.get("apiVersion").matches("\\d{4}-\\d{2}-\\d{2}(-preview)?"))) throw invalid();
        Set<UpstreamProtocol> protocols = new HashSet<>();
        for (CapabilityBindingReqDTO binding : normalize(bindings)) {
            ModelOperation operation = ModelOperation.valueOf(binding.getOperation());
            UpstreamProtocol protocol = UpstreamProtocol.valueOf(binding.getUpstreamProtocol());
            if (!supported(provider, operation, protocol)) throw invalid();
            protocols.add(protocol);
        }
        // 一条服务仅保存一种凭据模式，不能混用 IAM、无凭据和 API Key 协议。
        if (protocols.contains(UpstreamProtocol.BEDROCK_CONVERSE)
                && protocols.size() != 1) throw invalid();
        if (protocols.contains(UpstreamProtocol.OLLAMA_NATIVE)
                && protocols.size() != 1) throw invalid();
        if (protocols.contains(UpstreamProtocol.BEDROCK_CONVERSE)) {
            if (credential != CredentialMode.AWS_CHAIN || !StringUtils.hasText(options.get("region"))) throw invalid();
        } else if (protocols.contains(UpstreamProtocol.OLLAMA_NATIVE)) {
            if (credential != CredentialMode.NONE || !StringUtils.hasText(apiUrl)) throw invalid();
        } else if (credential != CredentialMode.API_KEY || !StringUtils.hasText(apiKey)) {
            throw invalid();
        }
        if (!protocols.equals(Set.of(UpstreamProtocol.BEDROCK_CONVERSE)) && !StringUtils.hasText(apiUrl)) throw invalid();
        if (protocols.contains(UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT)
                && (!StringUtils.hasText(options.get("deployment")) || !StringUtils.hasText(options.get("apiVersion")))) throw invalid();
        if (protocols.contains(UpstreamProtocol.AZURE_OPENAI_V1) && options.containsKey("deployment")) throw invalid();
        if (options.containsKey("region") && !protocols.contains(UpstreamProtocol.BEDROCK_CONVERSE)) throw invalid();
        if (options.containsKey("deployment") && !protocols.contains(UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT)) throw invalid();
        return credential;
    }

    public static boolean supported(String provider, ModelOperation operation, UpstreamProtocol protocol) {
        String name = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (protocol == UpstreamProtocol.OPENAI_COMPAT) return operation != ModelOperation.RERANK;
        return switch (protocol) {
            case ANTHROPIC_MESSAGES -> name.equals("anthropic") && operation == ModelOperation.CHAT_COMPLETIONS;
            case GEMINI_GENERATE_CONTENT -> name.equals("gemini") && EnumSet.of(ModelOperation.CHAT_COMPLETIONS,
                    ModelOperation.EMBEDDINGS, ModelOperation.IMAGE_GENERATION).contains(operation);
            case AZURE_OPENAI_V1 -> name.equals("azure") && operation != ModelOperation.RERANK;
            case AZURE_OPENAI_DEPLOYMENT -> name.equals("azure") && EnumSet.of(ModelOperation.CHAT_COMPLETIONS,
                    ModelOperation.EMBEDDINGS, ModelOperation.IMAGE_GENERATION, ModelOperation.TRANSCRIPTION,
                    ModelOperation.SPEECH).contains(operation);
            case BEDROCK_CONVERSE -> name.equals("bedrock") && operation == ModelOperation.CHAT_COMPLETIONS;
            case OLLAMA_NATIVE -> name.equals("ollama") && EnumSet.of(ModelOperation.CHAT_COMPLETIONS,
                    ModelOperation.EMBEDDINGS).contains(operation);
            case OPENROUTER_RERANK -> name.equals("openrouter") && operation == ModelOperation.RERANK;
            default -> false;
        };
    }

    private static ClientException invalid() {
        return new ClientException(LlmManageErrorCodeEnum.LLM_CAPABILITY_INVALID);
    }

    private static void validatePlayground(String value) {
        if (value == null) {
            throw invalid();
        }
        try (JsonParser parser = JSON_FACTORY.createParser(value)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalid();
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken token = parser.nextToken();
                switch (name) {
                    case "system" -> {
                        if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
                            throw invalid();
                        }
                    }
                    case "maxTokens" -> {
                        if (!isNumeric(token)
                                || !JsonNumbers.isPositiveInteger(parser.getDecimalValue(), Integer.MAX_VALUE)) {
                            throw invalid();
                        }
                    }
                    case "temperature" -> validateRange(parser, MAX_TEMPERATURE);
                    case "topP" -> validateRange(parser, MAX_TOP_P);
                    default -> throw invalid();
                }
            }
            if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                throw invalid();
            }
        } catch (IOException | NumberFormatException exception) {
            throw invalid();
        }
    }

    private static void validateRange(JsonParser parser, double upperBound) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            // 已有模型配置允许把参数范围存为嵌套的 JSON 字符串。
            try (JsonParser nested = JSON_FACTORY.createParser(parser.getText())) {
                nested.nextToken();
                validateRangeObject(nested, upperBound);
                if (nested.nextToken() != null) {
                    throw invalid();
                }
            }
            return;
        }
        validateRangeObject(parser, upperBound);
    }

    private static void validateRangeObject(JsonParser parser, double upperBound) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw invalid();
        }
        Double min = null;
        Double max = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            if (!isNumeric(parser.nextToken())) {
                throw invalid();
            }
            switch (name) {
                case "min" -> min = parser.getDoubleValue();
                case "max" -> max = parser.getDoubleValue();
                default -> throw invalid();
            }
        }
        if (parser.currentToken() != JsonToken.END_OBJECT || min == null || max == null
                || !Double.isFinite(min) || !Double.isFinite(max) || min < 0 || max < min || max > upperBound) {
            throw invalid();
        }
    }

    private static boolean isNumeric(JsonToken token) {
        return token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT;
    }
}
