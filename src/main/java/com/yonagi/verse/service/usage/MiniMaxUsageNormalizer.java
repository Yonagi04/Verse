package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** MiniMax OpenAI-like 与缓存专用字段用量解析器。 */
@Component
@Order(9)
public class MiniMaxUsageNormalizer implements UsageNormalizer {
    @Override
    public boolean supports(String provider, JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        return OpenAiCompatibleUsageNormalizer.providerIs(provider, "minimax") && usage != null
                && (usage.containsKey("cache_tokens") || usage.containsKey("prompt_cache_hit_tokens")
                || usage.containsKey("prompt_tokens_details"));
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        if (OpenAiCompatibleUsageNormalizer.hasMalformedLong(usage, "prompt_tokens", "completion_tokens",
                "total_tokens", "cache_tokens", "prompt_cache_hit_tokens")) {
            return UsageBreakdown.unavailable("minimax", usage);
        }
        Long input = OpenAiCompatibleUsageNormalizer.longValue(usage, "prompt_tokens", "input_tokens");
        Long output = OpenAiCompatibleUsageNormalizer.longValue(usage, "completion_tokens", "output_tokens");
        Long cached = OpenAiCompatibleUsageNormalizer.longValue(usage, "cache_tokens", "prompt_cache_hit_tokens");
        JSONObject details = OpenAiCompatibleUsageNormalizer.objectValue(usage, "prompt_tokens_details");
        if (usage != null && usage.get("prompt_tokens_details") != null && details == null) {
            return UsageBreakdown.unavailable("minimax", usage);
        }
        if (cached == null) {
            cached = OpenAiCompatibleUsageNormalizer.longValue(details, "cached_tokens");
        }
        if (cached == null) {
            cached = 0L;
        }
        return OpenAiCompatibleUsageNormalizer.valid(input, cached, 0L, output,
                OpenAiCompatibleUsageNormalizer.longValue(usage, "total_tokens"), "minimax", usage);
    }
}
