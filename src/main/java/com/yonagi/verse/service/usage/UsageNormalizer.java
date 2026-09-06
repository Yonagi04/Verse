package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;

public interface UsageNormalizer {
    boolean supports(String provider, JSONObject response);

    UsageBreakdown normalize(JSONObject response);
}
