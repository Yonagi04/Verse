package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class DeepSeekUsageNormalizer implements UsageNormalizer {
    @Override
    public boolean supports(String provider, JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        return OpenAiCompatibleUsageNormalizer.providerIs(provider, "deepseek") && usage != null
                && (usage.containsKey("prompt_cache_hit_tokens") || usage.containsKey("prompt_cache_miss_tokens"));
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        Long hit = OpenAiCompatibleUsageNormalizer.longValue(usage, "prompt_cache_hit_tokens");
        Long miss = OpenAiCompatibleUsageNormalizer.longValue(usage, "prompt_cache_miss_tokens");
        Long output = OpenAiCompatibleUsageNormalizer.longValue(usage, "completion_tokens");
        if (OpenAiCompatibleUsageNormalizer.hasMalformedLong(usage, "prompt_cache_hit_tokens",
                "prompt_cache_miss_tokens", "prompt_tokens", "completion_tokens", "total_tokens")
                || hit == null || miss == null || output == null) {
            return UsageBreakdown.unavailable("deepseek", usage);
        }
        Long prompt = OpenAiCompatibleUsageNormalizer.longValue(usage, "prompt_tokens");
        try {
            long input = Math.addExact(hit, miss);
            if (prompt != null && !prompt.equals(input)) {
                return UsageBreakdown.unavailable("deepseek", usage);
            }
            Long total = OpenAiCompatibleUsageNormalizer.longValue(usage, "total_tokens");
            return OpenAiCompatibleUsageNormalizer.valid(input, hit, 0L, output,
                    total == null ? Math.addExact(input, output) : total, "deepseek", usage);
        } catch (ArithmeticException ignored) {
            return UsageBreakdown.unavailable("deepseek", usage);
        }
    }
}
