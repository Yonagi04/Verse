package com.yonagi.verse.adapter.skillhub;

import com.yonagi.verse.service.skillhub.port.SkillPublicQueryRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/** 本机无独立网关，由应用在公开SQL入口执行IP及总预算，不新增中间件。 */
@Component
@RequiredArgsConstructor
public class SkillRedisPublicQueryRateLimiter implements SkillPublicQueryRateLimiter {
    private static final String PREFIX = "verse:{skill-query}:";
    private final SkillRedisRateCounter counter;

    @Override
    public void check(String ipHash) {
        counter.check(List.of(PREFIX + "ip:" + ipHash, PREFIX + "global"), "120", "600");
    }
}
