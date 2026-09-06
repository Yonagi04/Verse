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
        return usageOf(response) != null;
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        JSONObject usage = usageOf(response);
        if (usage == null) {
            return UsageBreakdown.unavailable("openai-compatible", null);
        }
        Long input = longValue(usage, "prompt_tokens", "input_tokens");
        Long output = longValue(usage, "completion_tokens", "output_tokens");
        Object promptDetailsValue = usage.get("prompt_tokens_details");
        Object inputDetailsValue = usage.get("input_tokens_details");
        if ((promptDetailsValue != null && !(promptDetailsValue instanceof JSONObject))
                || (inputDetailsValue != null && !(inputDetailsValue instanceof JSONObject))) {
            return UsageBreakdown.unavailable("openai-compatible", usage);
        }
        JSONObject details = promptDetailsValue instanceof JSONObject json ? json : null;
        if (details == null) {
            details = inputDetailsValue instanceof JSONObject json ? json : null;
        }
        Long cached = details == null ? longValue(usage, "cached_tokens") : longValue(details, "cached_tokens");
        if (hasMalformedLong(usage, "prompt_tokens", "input_tokens", "completion_tokens", "output_tokens",
                "total_tokens", "cached_tokens")
                || hasMalformedLong(details, "cached_tokens")) {
            return UsageBreakdown.unavailable("openai-compatible", usage);
        }
        if (cached == null) {
            cached = 0L;
        }
        Long total = longValue(usage, "total_tokens");
        return valid(input, cached, 0L, output, total, "openai-compatible", usage);
    }

    protected static JSONObject usageOf(JSONObject response) {
        if (response == null) {
            return null;
        }
        return response.getJSONObject("usage");
    }

    protected static Long longValue(JSONObject object, String... names) {
        if (object == null) {
            return null;
        }
        for (String name : names) {
            try {
                Long value = object.getLong(name);
                if (value != null) return value;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    protected static boolean hasMalformedLong(JSONObject object, String... names) {
        if (object == null) {
            return false;
        }
        for (String name : names) {
            if (!object.containsKey(name) || object.get(name) == null) {
                continue;
            }
            try {
                object.getLong(name);
            } catch (RuntimeException ignored) {
                return true;
            }
        }
        return false;
    }

    protected static boolean providerIs(String provider, String expected) {
        return provider != null && expected.equalsIgnoreCase(provider.trim());
    }

    protected static JSONObject objectValue(JSONObject object, String name) {
        if (object == null) {
            return null;
        }
        Object value = object.get(name);
        return value instanceof JSONObject json ? json : null;
    }

    protected static UsageBreakdown withParser(UsageBreakdown usage, String parser) {
        return new UsageBreakdown(usage.inputTokens(), usage.cachedInputTokens(), usage.cacheWriteInputTokens(),
                usage.outputTokens(), usage.totalTokens(), usage.source(), parser, usage.rawUsage(), usage.valid());
    }

    protected static UsageBreakdown valid(Long input, Long cached, Long cacheWrite, Long output, Long total,
                                          String parser, JSONObject raw) {
        if (input == null || output == null || cached == null || cacheWrite == null
                || input < 0 || cached < 0 || cacheWrite < 0 || output < 0 || cached > input
                || (total != null && total < 0)) {
            return UsageBreakdown.unavailable(parser, raw);
        }
        try {
            long expectedTotal = Math.addExact(input, output);
            if (total != null && !total.equals(expectedTotal)) {
                return UsageBreakdown.unavailable(parser, raw);
            }
            return new UsageBreakdown(input, cached, cacheWrite, output,
                    total == null ? expectedTotal : total, "EXACT", parser, raw, true);
        } catch (ArithmeticException ignored) {
            return UsageBreakdown.unavailable(parser, raw);
        }
    }
}
