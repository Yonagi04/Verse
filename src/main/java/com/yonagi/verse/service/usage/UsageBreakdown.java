package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;

/**
 * 提供方用量的标准化结果；空值表示无法获取，不能按零处理。
 */
public record UsageBreakdown(
        Long inputTokens,
        Long cachedInputTokens,
        Long cacheWriteInputTokens,
        Long outputTokens,
        Long totalTokens,
        String source,
        String parser,
        JSONObject rawUsage,
        boolean valid) {

    public static UsageBreakdown unavailable(String parser, JSONObject rawUsage) {
        return new UsageBreakdown(null, null, null, null, null, "UNKNOWN", parser, rawUsage, false);
    }
}
