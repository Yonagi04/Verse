package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** OpenAI Chat/Completions 与 Responses 用量解析器。 */
@Component
@Order(1)
public class OpenAiUsageNormalizer implements UsageNormalizer {
    private final OpenAiCompatibleUsageNormalizer delegate = new OpenAiCompatibleUsageNormalizer();

    @Override
    public boolean supports(String provider, JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        return OpenAiCompatibleUsageNormalizer.providerIs(provider, "openai") && usage != null
                && (usage.containsKey("prompt_tokens") || usage.containsKey("input_tokens")
                || usage.containsKey("completion_tokens") || usage.containsKey("output_tokens"));
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        return OpenAiCompatibleUsageNormalizer.withParser(delegate.normalize(response), "openai");
    }
}
