package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CredentialMode;
import com.yonagi.verse.common.enums.LlmManageErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.dto.req.CapabilityBindingReqDTO;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 服务配置校验，禁止供应商名称隐式决定协议。 */
public final class CapabilityConfiguration {
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
        if (!Set.of("deployment", "apiVersion", "region").containsAll(options.keySet())) throw invalid();
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
}
