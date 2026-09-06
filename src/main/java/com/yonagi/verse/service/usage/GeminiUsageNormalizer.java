package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class GeminiUsageNormalizer implements UsageNormalizer {
    @Override
    public boolean supports(String provider, JSONObject response) {
        return response != null && response.getJSONObject("usageMetadata") != null;
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        JSONObject usage = response.getJSONObject("usageMetadata");
        Long input = OpenAiCompatibleUsageNormalizer.longValue(usage, "promptTokenCount");
        Long cached = OpenAiCompatibleUsageNormalizer.longValue(usage, "cachedContentTokenCount");
        Long candidate = OpenAiCompatibleUsageNormalizer.longValue(usage, "candidatesTokenCount");
        Long thoughts = OpenAiCompatibleUsageNormalizer.longValue(usage, "thoughtsTokenCount");
        cached = cached == null ? 0L : cached;
        candidate = candidate == null ? 0L : candidate;
        thoughts = thoughts == null ? 0L : thoughts;
        Long output = candidate + thoughts;
        return OpenAiCompatibleUsageNormalizer.valid(input, cached, 0L, output, input == null ? null : input + output,
                "gemini", usage);
    }
}
