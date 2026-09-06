package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Kimi 顶层 cached_tokens 用量解析器。 */
@Component
@Order(8)
public class KimiUsageNormalizer implements UsageNormalizer {
    private final OpenAiCompatibleUsageNormalizer delegate = new OpenAiCompatibleUsageNormalizer();

    @Override
    public boolean supports(String provider, JSONObject response) {
        JSONObject usage = OpenAiCompatibleUsageNormalizer.usageOf(response);
        return OpenAiCompatibleUsageNormalizer.providerIs(provider, "kimi") && usage != null
                && usage.containsKey("cached_tokens");
    }

    @Override
    public UsageBreakdown normalize(JSONObject response) {
        return OpenAiCompatibleUsageNormalizer.withParser(delegate.normalize(response), "kimi");
    }
}
