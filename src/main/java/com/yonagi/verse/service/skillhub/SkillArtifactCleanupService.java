package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.enums.SkillDraftStatusEnum;
import com.yonagi.verse.common.enums.SkillImportTaskStatusEnum;
import com.yonagi.verse.common.enums.SkillArtifactStatusEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactFileDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactFileMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillCleanupMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/** 删除先以制品行锁复核引用并标记，再在事务外删除S3，失败保持可恢复状态。 */
@Service
@RequiredArgsConstructor
public class SkillArtifactCleanupService {
    private static final int DRAFT_RETENTION_DAYS = 30;
    private static final String CLEANUP_BATCH_LIMIT = "LIMIT 500";
    private static final Set<String> SOURCE_TERMINAL = Set.of(SkillImportTaskStatusEnum.SUCCEEDED.name(),
            SkillImportTaskStatusEnum.FAILED.name(), SkillImportTaskStatusEnum.CANCELLED.name(),
            SkillImportTaskStatusEnum.WAITING_UPLOAD.name());
    private final SkillArtifactMapper artifactMapper;
    private final SkillArtifactFileMapper fileMapper;
    private final SkillImportTaskMapper taskMapper;
    private final SkillCleanupMapper cleanupMapper;
    private final SkillDraftMapper draftMapper;

    /**
     * @param artifactId 删除候选
     * @return 可删私有Key；有引用或已处理时null
     */
    @Transactional(rollbackFor = Exception.class)
    public String claimArtifact(Long artifactId) {
        SkillArtifactDO artifact = artifactMapper.selectByIdForUpdate(artifactId);
        if (artifact == null || SkillArtifactStatusEnum.DELETED.name().equals(artifact.getStatus())
                || cleanupMapper.selectArtifactReferenced(artifactId)) {
            return null;
        }
        int changed = artifactMapper.update(null, Wrappers.lambdaUpdate(SkillArtifactDO.class)
                .eq(SkillArtifactDO::getArtifactId, artifactId)
                .in(SkillArtifactDO::getStatus, "READY", SkillArtifactStatusEnum.DELETE_PENDING.name())
                .set(SkillArtifactDO::getStatus, SkillArtifactStatusEnum.DELETE_PENDING.name())
                .set(SkillArtifactDO::getUpdateTime, LocalDateTime.now(ZoneOffset.UTC)));
        return changed == 1 ? artifact.getArchiveKey() : null;
    }

    /**
     * @param artifactId 已标记制品
     * @param key 已成功删除的不可变Key
     */
    @Transactional(rollbackFor = Exception.class)
    public void artifactDeleted(Long artifactId, String key) {
        int changed = artifactMapper.update(null, Wrappers.lambdaUpdate(SkillArtifactDO.class)
                .eq(SkillArtifactDO::getArtifactId, artifactId).eq(SkillArtifactDO::getArchiveKey, key)
                .eq(SkillArtifactDO::getStatus, SkillArtifactStatusEnum.DELETE_PENDING.name())
                .set(SkillArtifactDO::getStatus, SkillArtifactStatusEnum.DELETED.name())
                .set(SkillArtifactDO::getSkillMarkdown, "").set(SkillArtifactDO::getManifestJson, "{}")
                .set(SkillArtifactDO::getUpdateTime, LocalDateTime.now(ZoneOffset.UTC)));
        if (changed == 1) {
            fileMapper.delete(Wrappers.lambdaQuery(SkillArtifactFileDO.class)
                    .eq(SkillArtifactFileDO::getArtifactId, artifactId));
        }
    }

    /**
     * @param jobId 源包过期候选
     * @return 终态源Key；上传中或新代次不会被删除
     */
    @Transactional(rollbackFor = Exception.class)
    public String sourceKey(Long jobId) {
        SkillImportTaskDO task = taskMapper.selectByIdForUpdate(jobId);
        if (task == null || !SOURCE_TERMINAL.contains(task.getStatus())) {
            return null;
        }
        // WAITING_UPLOAD来自失败回退，不能留着旧代次Key供下一次恢复；新上传总是分配新Key。
        return task.getSourceKey();
    }

    /**
     * @param jobId 原源包任务
     * @param key 已成功删除的Key；新上传代次不能被清空
     */
    public void sourceDeleted(Long jobId, String key) {
        taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getJobId, jobId).eq(SkillImportTaskDO::getSourceKey, key)
                .in(SkillImportTaskDO::getStatus, SOURCE_TERMINAL)
                .set(SkillImportTaskDO::getSourceKey, null).set(SkillImportTaskDO::getSourceSha256, null)
                .set(SkillImportTaskDO::getSourceBytes, null));
    }

    /**
     * @param userId 已冻结注销账号；只抹除未提交表单，不改已批准版本事实
     */
    public void eraseClosedDrafts(Long userId) {
        draftMapper.update(null, Wrappers.lambdaUpdate(SkillDraftDO.class)
                .eq(SkillDraftDO::getOwnerUserId, userId)
                .in(SkillDraftDO::getStatus, SkillDraftStatusEnum.CANCELLED.name(), SkillDraftStatusEnum.EXPIRED.name())
                .and(fields -> fields.isNotNull(SkillDraftDO::getMetadataJson)
                        .or().isNotNull(SkillDraftDO::getArtifactId).or().isNotNull(SkillDraftDO::getVersionLabel))
                .set(SkillDraftDO::getMetadataJson, null).set(SkillDraftDO::getVersionLabel, null)
                .set(SkillDraftDO::getArtifactId, null).last(CLEANUP_BATCH_LIMIT));
    }

    /**
     * @param draftId 超过30天且仍可编辑的候选；执行代次同时因草稿状态失效
     */
    @Transactional(rollbackFor = Exception.class)
    public void expireDraft(Long draftId) {
        SkillDraftDO reference = draftMapper.selectById(draftId);
        if (reference == null) {
            return;
        }
        // 无后续账号/发布者锁，仅锁草稿后更新任务；其他路径统一在草稿之后拿任务。
        SkillDraftDO draft = draftMapper.selectByIdForUpdate(draftId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (draft == null || !Set.of("EDITING", "READY").contains(draft.getStatus())
                || draft.getUpdateTime().isAfter(now.minusDays(DRAFT_RETENTION_DAYS))) {
            return;
        }
        draft.setStatus(SkillDraftStatusEnum.EXPIRED.name());
        draft.setRevision(draft.getRevision() + 1);
        draft.setUpdateTime(now);
        draftMapper.updateById(draft);
        taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getDraftId, draftId).notIn(SkillImportTaskDO::getStatus,
                        SkillImportTaskStatusEnum.SUCCEEDED.name(), SkillImportTaskStatusEnum.FAILED.name(),
                        SkillImportTaskStatusEnum.CANCELLED.name())
                .set(SkillImportTaskDO::getStatus, SkillImportTaskStatusEnum.CANCELLED.name())
                .set(SkillImportTaskDO::getUpdateTime, now));
    }
}
