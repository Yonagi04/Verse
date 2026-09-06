package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class UsageNormalizerRegistry {
    private static final Set<String> CORE_PROVIDERS = Set.of(
            "openai", "anthropic", "gemini", "deepseek", "zhipu", "qwen", "doubao", "kimi", "minimax");

    private final List<UsageNormalizer> normalizers;

    public UsageBreakdown normalize(String provider, JSONObject response) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase();
        if (CORE_PROVIDERS.contains(normalizedProvider)) {
            UsageNormalizer dedicated = normalizers.stream()
                    .filter(normalizer -> !(normalizer instanceof OpenAiCompatibleUsageNormalizer))
                    .filter(normalizer -> supportsSafely(normalizer, provider, response))
                    .findFirst()
                    .orElse(null);
            if (dedicated != null) {
                return safelyNormalize(dedicated, response, normalizedProvider);
            }
        }
        return normalizers.stream()
                .filter(OpenAiCompatibleUsageNormalizer.class::isInstance)
                .findFirst()
                .map(normalizer -> safelyNormalize(normalizer, response, "openai-compatible"))
                .orElseGet(() -> UsageBreakdown.unavailable("openai-compatible",
                        response == null ? null : response.getJSONObject("usage")));
    }

    private UsageBreakdown safelyNormalize(UsageNormalizer normalizer, JSONObject response, String parser) {
        try {
            return normalizer.normalize(response);
        } catch (RuntimeException ignored) {
            JSONObject raw = OpenAiCompatibleUsageNormalizer.objectValue(response, "usage");
            if (raw == null) {
                raw = OpenAiCompatibleUsageNormalizer.objectValue(response, "usageMetadata");
            }
            return UsageBreakdown.unavailable(parser, raw);
        }
    }

    private boolean supportsSafely(UsageNormalizer normalizer, String provider, JSONObject response) {
        try {
            return normalizer.supports(provider, response);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
