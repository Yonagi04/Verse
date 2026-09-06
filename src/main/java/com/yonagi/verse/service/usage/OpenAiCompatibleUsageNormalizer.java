package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容响应结构的兜底用量解析器。
 */
@Component
@Order(100)
public class OpenAiCompatibleUsageNormalizer implements UsageNormalizer {
    @Override
    public boolean supports(String provider, JSONObject response) {
        return true;
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        JSONObject usage = usageOf(response);
        if (usage == null) {
            return UsageBreakdown.unavailable("openai-compatible", null);
        }
        Long input = longValue(usage, "prompt_tokens", "input_tokens");
        Long output = longValue(usage, "completion_tokens", "output_tokens");
        JSONObject details = usage.getJSONObject("prompt_tokens_details");
        if (details == null) {
            details = usage.getJSONObject("input_tokens_details");
        }
        Long cached = details == null ? longValue(usage, "cached_tokens") : longValue(details, "cached_tokens");
        if (cached == null) {
            cached = 0L;
        }
        Long total = longValue(usage, "total_tokens");
        if (total == null && input != null && output != null) {
            total = input + output;
        }
        return valid(input, cached, 0L, output, total, "openai-compatible", usage);
    }

    protected static JSONObject usageOf(JSONObject response) {
        if (response == null) {
            return null;
        }
        return response.getJSONObject("usage") != null
                ? response.getJSONObject("usage")
                : response.getJSONObject("usageMetadata");
    }

    protected static Long longValue(JSONObject object, String... names) {
        if (object == null) {
            return null;
        }
        for (String name : names) {
            Long value = object.getLong(name);
            if (value != null) return value;
        }
        return null;
    }

    protected static UsageBreakdown valid(Long input, Long cached, Long cacheWrite, Long output, Long total,
                                          String parser, JSONObject raw) {
        if (input == null || output == null || cached == null || cacheWrite == null
                || input < 0 || cached < 0 || cacheWrite < 0 || output < 0 || cached > input
                || (total != null && total < 0) || (total != null && !total.equals(input + output))) {
            return UsageBreakdown.unavailable(parser, raw);
        }
        return new UsageBreakdown(input, cached, cacheWrite, output, total == null ? input + output : total,
                "EXACT", parser, raw, true);
    }
}
