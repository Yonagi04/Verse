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
        return usage != null && ("deepseek".equalsIgnoreCase(provider) || usage.containsKey("prompt_cache_hit_tokens"));
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        Long hit = OpenAiCompatibleUsageNormalizer.longValue(usage, "prompt_cache_hit_tokens");
        Long miss = OpenAiCompatibleUsageNormalizer.longValue(usage, "prompt_cache_miss_tokens");
        Long output = OpenAiCompatibleUsageNormalizer.longValue(usage, "completion_tokens");
        if (hit == null || miss == null) {
            return UsageBreakdown.unavailable("deepseek", usage);
        }
        Long prompt = OpenAiCompatibleUsageNormalizer.longValue(usage, "prompt_tokens");
        if (prompt != null && !prompt.equals(hit + miss)) {
            return UsageBreakdown.unavailable("deepseek", usage);
        }
        return OpenAiCompatibleUsageNormalizer.valid(hit + miss, hit, 0L, output,
                hit + miss + (output == null ? 0 : output), "deepseek", usage);
    }
}
