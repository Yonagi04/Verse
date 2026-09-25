package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.common.enums.LLMProviderEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdapterRegistryTest {
    private AdapterRegistration registration(ModelOperation operation, UpstreamProtocol protocol, String provider) {
        return new AdapterRegistration() {
            public ModelOperation operation() { return operation; }
            public UpstreamProtocol protocol() { return protocol; }
            public String provider() { return provider; }
        };
    }

    @Test
    void sharedCompatibilityMatchesUnknownProviderOnlyWhenExplicitlySelected() {
        var compatible = registration(ModelOperation.CHAT_COMPLETIONS, UpstreamProtocol.OPENAI_COMPAT, null);
        var registry = new AdapterRegistry(List.of(compatible));
        assertSame(compatible, registry.select("custom-vendor", ModelOperation.CHAT_COMPLETIONS,
                UpstreamProtocol.OPENAI_COMPAT));
        assertThrows(ClientException.class, () -> registry.select("custom-vendor", ModelOperation.CHAT_COMPLETIONS,
                UpstreamProtocol.ANTHROPIC_MESSAGES));
        assertThrows(ClientException.class, () -> registry.select("custom-vendor", ModelOperation.EMBEDDINGS,
                UpstreamProtocol.OPENAI_COMPAT));
    }

    @Test
    void nativeSelectionChecksProviderAndAmbiguity() {
        var nativeAdapter = registration(ModelOperation.CHAT_COMPLETIONS, UpstreamProtocol.ANTHROPIC_MESSAGES,
                "anthropic");
        var registry = new AdapterRegistry(List.of(nativeAdapter));
        assertSame(nativeAdapter, registry.select("ANTHROPIC", ModelOperation.CHAT_COMPLETIONS,
                UpstreamProtocol.ANTHROPIC_MESSAGES));
        assertThrows(ClientException.class, () -> registry.select("openai", ModelOperation.CHAT_COMPLETIONS,
                UpstreamProtocol.ANTHROPIC_MESSAGES));
        var ambiguous = new AdapterRegistry(List.of(nativeAdapter, nativeAdapter));
        assertThrows(ClientException.class, () -> ambiguous.select("anthropic", ModelOperation.CHAT_COMPLETIONS,
                UpstreamProtocol.ANTHROPIC_MESSAGES));
    }

    @Test
    void everyCatalogLabelAndArbitrarySupplierCanUseExplicitCompatibleChat() {
        var compatible = registration(ModelOperation.CHAT_COMPLETIONS, UpstreamProtocol.OPENAI_COMPAT, null);
        var registry = new AdapterRegistry(List.of(compatible));
        for (LLMProviderEnum provider : LLMProviderEnum.values()) {
            assertSame(compatible, registry.select(provider.getProvider(), ModelOperation.CHAT_COMPLETIONS,
                    UpstreamProtocol.OPENAI_COMPAT));
        }
        assertSame(compatible, registry.select("my-new-vendor", ModelOperation.CHAT_COMPLETIONS,
                UpstreamProtocol.OPENAI_COMPAT));
    }
}
