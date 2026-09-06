package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class AnthropicUsageNormalizer implements UsageNormalizer {
    @Override
    public boolean supports(String provider, JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        return usage != null && ("anthropic".equalsIgnoreCase(provider)
                || usage.containsKey("cache_creation_input_tokens") || usage.containsKey("cache_read_input_tokens"));
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        Long base = OpenAiCompatibleUsageNormalizer.longValue(usage, "input_tokens");
        Long write = OpenAiCompatibleUsageNormalizer.longValue(usage, "cache_creation_input_tokens");
        Long cached = OpenAiCompatibleUsageNormalizer.longValue(usage, "cache_read_input_tokens");
        Long output = OpenAiCompatibleUsageNormalizer.longValue(usage, "output_tokens");
        if (base == null) {
            return UsageBreakdown.unavailable("anthropic", usage);
        }
        write = write == null ? 0L : write;
        cached = cached == null ? 0L : cached;
        return OpenAiCompatibleUsageNormalizer.valid(base + write + cached, cached, write, output,
                base + write + cached + (output == null ? 0 : output), "anthropic", usage);
    }
}
