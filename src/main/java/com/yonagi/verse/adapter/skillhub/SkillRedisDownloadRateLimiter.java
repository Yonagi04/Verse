package com.yonagi.verse.adapter.skillhub;

import com.yonagi.verse.service.skillhub.port.SkillDownloadRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/** 三个计数同一Lua检查并更新；固定60秒窗口，异常失败关闭，无数据库授权缓存。 */
@Component
@RequiredArgsConstructor
public class SkillRedisDownloadRateLimiter implements SkillDownloadRateLimiter {
    private static final String PREFIX = "verse:{skill-download}:";
    private final SkillRedisRateCounter counter;

    @Override
    public void check(String subjectHash, String ipHash) {
        counter.check(List.of(PREFIX + "subject:" + subjectHash, PREFIX + "ip:" + ipHash, PREFIX + "global"),
                "30", "120", "600");
    }
}
