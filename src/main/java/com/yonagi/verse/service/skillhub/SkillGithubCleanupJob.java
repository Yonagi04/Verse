package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.dao.mapper.skillhub.SkillGithubSourceMapper;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 有界超期和源清理，单次依赖故障保留引用供后续恢复。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillGithubCleanupJob {
    private final SkillGithubSourceMapper sources;
    private final SkillGithubCleanupService service;
    private final SkillArtifactStore store;

    @Scheduled(fixedDelay = 60000L)
    public void cleanup() {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            for (Long jobId : sources.selectExpiredWaitIds(now)) {
                service.expire(jobId);
            }
            for (Long jobId : sources.selectCleanupIds(now.minusDays(7), now.minusDays(30))) {
                for (String key : service.keys(jobId)) {
                    store.delete(key);
                    service.deleted(jobId, key);
                }
                service.prune(jobId);
            }
        } catch (RuntimeException exception) {
            log.warn("GitHub源清理失败，保留引用待重试: type={}", exception.getClass().getSimpleName());
        }
    }
}
