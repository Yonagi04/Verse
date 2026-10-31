package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.dao.entity.skillhub.SkillDownloadGrantDO;
import com.yonagi.verse.dao.entity.skillhub.SkillIdempotencyRecordDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillCleanupMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDownloadGrantMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillIdempotencyRecordMapper;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillObjectPage;
import com.yonagi.verse.service.skillhub.port.SkillStoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 周期性有界恢复，包括无DB记录的S3孤儿发现；单次异常不终止后续调度。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillCleanupJob {
    private static final long CLEANUP_INTERVAL_MILLIS = 60000L;
    private static final int DRAFT_RETENTION_DAYS = 30;
    private static final int SOURCE_RETENTION_DAYS = 7;
    private static final String CLEANUP_BATCH_LIMIT = "LIMIT 500";
    private static final Duration ORPHAN_GRACE = Duration.ofHours(24);
    private static final Duration IN_FLIGHT_GRACE = Duration.ofMinutes(10);
    private final SkillCleanupMapper cleanupMapper;
    private final SkillArtifactCleanupService artifactCleanupService;
    private final SkillAccountCleanupService accountCleanupService;
    private final SkillArtifactStore artifactStore;
    private final SkillDownloadGrantMapper grantMapper;
    private final SkillIdempotencyRecordMapper commandMapper;
    // Spring单个fixedDelay任务串行访问；重启后从第一页重扫，删除仍需宽限和DB复核。
    private String objectCursor;
    private Instant objectScanStarted;

    /** 每批最多100个DB候选和500个对象，不加载全表或无限循环扫描。 */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MILLIS)
    public void cleanup() {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            for (Long draftId : cleanupMapper.selectExpiredDraftIds(now.minusDays(DRAFT_RETENTION_DAYS))) {
                artifactCleanupService.expireDraft(draftId);
            }
            for (Long artifactId : cleanupMapper.selectArtifactCandidates(now.minus(ORPHAN_GRACE))) {
                String key = artifactCleanupService.claimArtifact(artifactId);
                if (key != null) {
                    artifactStore.delete(key);
                    artifactCleanupService.artifactDeleted(artifactId, key);
                }
            }
            for (Long jobId : cleanupMapper.selectSourceCandidates(now.minusDays(SOURCE_RETENTION_DAYS))) {
                String key = artifactCleanupService.sourceKey(jobId);
                if (key != null) {
                    artifactStore.delete(key);
                    artifactCleanupService.sourceDeleted(jobId, key);
                }
            }
            for (Long userId : cleanupMapper.selectRunnableAccounts()) {
                artifactCleanupService.eraseClosedDrafts(userId);
            }
            Instant scanStarted = scanOrphans();
            if (scanStarted != null) {
                LocalDateTime cutoff = LocalDateTime.ofInstant(
                        scanStarted.minus(ORPHAN_GRACE).minus(IN_FLIGHT_GRACE), ZoneOffset.UTC);
                for (Long userId : cleanupMapper.selectSettleableAccounts(cutoff)) {
                    if (!cleanupMapper.selectPendingAccountObjects(userId)) {
                        accountCleanupService.settle(userId, cleanupMapper.selectRetainedPublications(userId));
                    }
                }
            }
            grantMapper.delete(Wrappers.lambdaQuery(SkillDownloadGrantDO.class)
                    .le(SkillDownloadGrantDO::getExpiresAt, now).last(CLEANUP_BATCH_LIMIT));
            commandMapper.delete(Wrappers.lambdaQuery(SkillIdempotencyRecordDO.class)
                    .le(SkillIdempotencyRecordDO::getExpiresAt, now).last(CLEANUP_BATCH_LIMIT));
        } catch (RuntimeException exception) {
            log.warn("Skill私有资源清理失败，保留状态待下次恢复: type={}", exception.getClass().getSimpleName());
        }
    }

    private Instant scanOrphans() {
        if (objectCursor == null) {
            objectScanStarted = Instant.now();
        }
        SkillObjectPage page = artifactStore.list(objectCursor);
        Instant cutoff = Instant.now().minus(ORPHAN_GRACE);
        for (SkillStoredObject object : page.objects()) {
            if (object.lastModified() != null && !object.lastModified().isAfter(cutoff)
                    && !cleanupMapper.selectObjectReferenced(object.key())) {
                artifactStore.delete(object.key());
            }
        }
        objectCursor = page.nextCursor();
        return objectCursor == null ? objectScanStarted : null;
    }
}
