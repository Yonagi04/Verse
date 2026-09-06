package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 通义千问 DashScope/Responses 用量解析器。 */
@Component
@Order(6)
public class QwenUsageNormalizer implements UsageNormalizer {
    private final OpenAiCompatibleUsageNormalizer delegate = new OpenAiCompatibleUsageNormalizer();

    @Override
    public boolean supports(String provider, JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        return OpenAiCompatibleUsageNormalizer.providerIs(provider, "qwen") && usage != null
                && (usage.containsKey("input_tokens") || usage.containsKey("output_tokens")
                || usage.containsKey("input_tokens_details"));
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        return OpenAiCompatibleUsageNormalizer.withParser(delegate.normalize(response), "qwen");
    }
}
