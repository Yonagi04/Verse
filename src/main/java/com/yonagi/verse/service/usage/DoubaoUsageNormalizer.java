package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 豆包 Chat/Responses 用量解析器。 */
@Component
@Order(7)
public class DoubaoUsageNormalizer implements UsageNormalizer {
    private final OpenAiCompatibleUsageNormalizer delegate = new OpenAiCompatibleUsageNormalizer();

    @Override
    public boolean supports(String provider, JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        return OpenAiCompatibleUsageNormalizer.providerIs(provider, "doubao") && usage != null
                && (usage.containsKey("input_tokens") || usage.containsKey("input_tokens_details")
                || usage.containsKey("prompt_tokens_details"));
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        return OpenAiCompatibleUsageNormalizer.withParser(delegate.normalize(response), "doubao");
    }
}
