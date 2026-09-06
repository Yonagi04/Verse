package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 智谱 Chat/Responses 缓存字段用量解析器。 */
@Component
@Order(5)
public class ZhipuUsageNormalizer implements UsageNormalizer {
    private final OpenAiCompatibleUsageNormalizer delegate = new OpenAiCompatibleUsageNormalizer();

    @Override
    public boolean supports(String provider, JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        return OpenAiCompatibleUsageNormalizer.providerIs(provider, "zhipu") && usage != null
                && (usage.containsKey("prompt_tokens_details") || usage.containsKey("input_tokens_details"));
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        return OpenAiCompatibleUsageNormalizer.withParser(delegate.normalize(response), "zhipu");
    }
}
