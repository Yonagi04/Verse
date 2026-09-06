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
        return OpenAiCompatibleUsageNormalizer.providerIs(provider, "anthropic") && usage != null
                && (usage.containsKey("input_tokens") || usage.containsKey("cache_creation_input_tokens")
                || usage.containsKey("cache_read_input_tokens"));
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        Long base = OpenAiCompatibleUsageNormalizer.longValue(usage, "input_tokens");
        Long write = OpenAiCompatibleUsageNormalizer.longValue(usage, "cache_creation_input_tokens");
        Long cached = OpenAiCompatibleUsageNormalizer.longValue(usage, "cache_read_input_tokens");
        Long output = OpenAiCompatibleUsageNormalizer.longValue(usage, "output_tokens");
        if (OpenAiCompatibleUsageNormalizer.hasMalformedLong(usage, "input_tokens", "cache_creation_input_tokens",
                "cache_read_input_tokens", "output_tokens") || base == null) {
            return UsageBreakdown.unavailable("anthropic", usage);
        }
        write = write == null ? 0L : write;
        cached = cached == null ? 0L : cached;
        if (output == null) {
            return UsageBreakdown.unavailable("anthropic", usage);
        }
        try {
            long input = Math.addExact(base, Math.addExact(write, cached));
            return OpenAiCompatibleUsageNormalizer.valid(input, cached, write, output,
                    Math.addExact(input, output), "anthropic", usage);
        } catch (ArithmeticException ignored) {
            return UsageBreakdown.unavailable("anthropic", usage);
        }
    }
}
