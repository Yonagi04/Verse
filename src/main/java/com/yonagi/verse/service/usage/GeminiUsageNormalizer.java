package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class GeminiUsageNormalizer implements UsageNormalizer {
    @Override
    public boolean supports(String provider, JSONObject response) {
        return OpenAiCompatibleUsageNormalizer.providerIs(provider, "gemini")
                && response != null && response.containsKey("usageMetadata");
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        JSONObject usage = response.getJSONObject("usageMetadata");
        Long input = OpenAiCompatibleUsageNormalizer.longValue(usage, "promptTokenCount");
        Long cached = OpenAiCompatibleUsageNormalizer.longValue(usage, "cachedContentTokenCount");
        Long candidate = OpenAiCompatibleUsageNormalizer.longValue(usage, "candidatesTokenCount");
        Long thoughts = OpenAiCompatibleUsageNormalizer.longValue(usage, "thoughtsTokenCount");
        Long total = OpenAiCompatibleUsageNormalizer.longValue(usage, "totalTokenCount");
        if (usage == null || OpenAiCompatibleUsageNormalizer.hasMalformedLong(usage, "promptTokenCount",
                "cachedContentTokenCount", "candidatesTokenCount", "thoughtsTokenCount", "totalTokenCount")) {
            return UsageBreakdown.unavailable("gemini", usage);
        }
        cached = cached == null ? 0L : cached;
        candidate = candidate == null ? 0L : candidate;
        thoughts = thoughts == null ? 0L : thoughts;
        try {
            Long output = Math.addExact(candidate, thoughts);
            Long expectedTotal = input == null ? null : Math.addExact(input, output);
            return OpenAiCompatibleUsageNormalizer.valid(input, cached, 0L, output,
                    total == null ? expectedTotal : total, "gemini", usage);
        } catch (ArithmeticException ignored) {
            return UsageBreakdown.unavailable("gemini", usage);
        }
    }
}
