package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CredentialMode;
import com.yonagi.verse.dto.req.CapabilityBindingReqDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityConfigurationTest {
    private CapabilityBindingReqDTO binding(String operation, String protocol) {
        CapabilityBindingReqDTO result = new CapabilityBindingReqDTO();
        result.setOperation(operation);
        result.setUpstreamProtocol(protocol);
        return result;
    }

    @Test
    void customSupplierMayUseExplicitOpenAiCompatibility() {
        var bindings = List.of(binding("CHAT_COMPLETIONS", "OPENAI_COMPAT"),
                binding("EMBEDDINGS", "OPENAI_COMPAT"));
        assertEquals(CredentialMode.API_KEY, CapabilityConfiguration.validate("my-custom-host",
                null, "https://example.invalid/v1", "secret", Map.of(), bindings));
    }

    @Test
    void customAndRemainingEnumeratedSuppliersCannotSelectNativeProtocols() {
        var nativeChat = List.of(binding("CHAT_COMPLETIONS", "ANTHROPIC_MESSAGES"));
        assertThrows(ClientException.class, () -> CapabilityConfiguration.validate("my-custom-host",
                null, "https://example.invalid", "secret", Map.of(), nativeChat));
        assertThrows(ClientException.class, () -> CapabilityConfiguration.validate("mistral",
                null, "https://example.invalid", "secret", Map.of(), nativeChat));
    }

    @Test
    void azureDeploymentNeedsVersionAndDeployment() {
        var bindings = List.of(binding("CHAT_COMPLETIONS", "AZURE_OPENAI_DEPLOYMENT"));
        assertThrows(ClientException.class, () -> CapabilityConfiguration.validate("azure", "API_KEY",
                "https://azure.example.invalid", "secret", Map.of("deployment", "gpt"), bindings));
        assertEquals(CredentialMode.API_KEY, CapabilityConfiguration.validate("azure", "API_KEY",
                "https://azure.example.invalid", "secret",
                Map.of("deployment", "gpt", "apiVersion", "2024-10-21"), bindings));
    }

    @Test
    void bedrockAndOllamaUseDedicatedCredentialModes() {
        var bedrock = List.of(binding("CHAT_COMPLETIONS", "BEDROCK_CONVERSE"));
        assertEquals(CredentialMode.AWS_CHAIN, CapabilityConfiguration.validate("bedrock", "AWS_CHAIN",
                null, null, Map.of("region", "us-east-1"), bedrock));
        assertThrows(ClientException.class, () -> CapabilityConfiguration.validate("bedrock", "AWS_CHAIN",
                null, null, Map.of(), bedrock));
        var ollama = List.of(binding("CHAT_COMPLETIONS", "OLLAMA_NATIVE"));
        assertEquals(CredentialMode.NONE, CapabilityConfiguration.validate("ollama", "NONE",
                "http://localhost:11434", null, Map.of(), ollama));
    }

    @Test
    void invalidOperationAndRerankCompatibilityAreRejected() {
        assertThrows(ClientException.class, () -> CapabilityConfiguration.normalize(
                List.of(binding("BOGUS", "OPENAI_COMPAT"))));
        assertThrows(ClientException.class, () -> CapabilityConfiguration.validate("openrouter", null,
                "https://example.invalid", "secret", Map.of(),
                List.of(binding("RERANK", "OPENAI_COMPAT"))));
    }
}
