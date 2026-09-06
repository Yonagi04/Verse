package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class UsageNormalizerRegistry {
    private final List<UsageNormalizer> normalizers;

    public UsageBreakdown normalize(String provider, JSONObject response) {
        return normalizers.stream()
                .filter(normalizer -> normalizer.supports(provider, response))
                .findFirst()
                .map(normalizer -> normalizer.normalize(response))
                .orElseGet(() -> UsageBreakdown.unavailable(provider, response));
    }
}
