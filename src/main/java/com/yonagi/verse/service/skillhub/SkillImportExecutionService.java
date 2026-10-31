package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.enums.SkillArtifactStatusEnum;
import com.yonagi.verse.common.enums.SkillDraftStatusEnum;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.enums.SkillImportTaskStatusEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactFileDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactFileMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/** Worker代次、租约与最终绑定的数据库权威边界，不包含S3调用或包解析。 */
@Service
@RequiredArgsConstructor
public class SkillImportExecutionService {
    private static final int LEASE_SECONDS = 30;
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_SECONDS = 15;
    private static final Set<String> EDITABLE = Set.of("EDITING", "READY");
    private final SkillAccessPolicy accessPolicy;
    private final AccountAvailabilityService accountAvailabilityService;
    private final SkillImportTaskMapper taskMapper;
    private final SkillDraftMapper draftMapper;
    private final SkillArtifactMapper artifactMapper;
    private final SkillArtifactFileMapper fileMapper;
    private final ObjectMapper objectMapper;

    /**
     * @param jobId 调度候选
     * @return 独占代次；候选已被他人处理或失效时null
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillExecutionClaim claim(Long jobId) {
        SkillImportTaskDO reference = taskMapper.selectById(jobId);
        if (reference == null) {
            return null;
        }
        if (!accountAvailabilityService.isAccountActive(reference.getOwnerUserId())) {
            taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                    .eq(SkillImportTaskDO::getJobId, jobId).in(SkillImportTaskDO::getStatus,
                            SkillImportTaskStatusEnum.QUEUED.name(), SkillImportTaskStatusEnum.RETRY_WAIT.name(),
                            SkillImportTaskStatusEnum.RUNNING.name())
                    .set(SkillImportTaskDO::getStatus, SkillImportTaskStatusEnum.CANCELLED.name())
                    .set(SkillImportTaskDO::getUpdateTime, LocalDateTime.now(ZoneOffset.UTC)));
            return null;
        }
        SkillOwnedDraft owned = accessPolicy.lockDraft(reference.getOwnerUserId(), reference.getDraftId());
        SkillImportTaskDO task = taskMapper.selectByIdForUpdate(jobId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean queued = Set.of(SkillImportTaskStatusEnum.QUEUED.name(), SkillImportTaskStatusEnum.RETRY_WAIT.name())
                .contains(task.getStatus())
                && !task.getNextRetryAt().isAfter(now);
        boolean abandoned = SkillImportTaskStatusEnum.RUNNING.name().equals(task.getStatus())
                && task.getLeaseUntil() != null && !task.getLeaseUntil().isAfter(now);
        if (!queued && !abandoned) {
            return null;
        }
        if (!jobId.equals(owned.draft().getLatestJobId()) || !EDITABLE.contains(owned.draft().getStatus())) {
            task.setStatus(SkillImportTaskStatusEnum.CANCELLED.name());
            task.setUpdateTime(now);
            taskMapper.updateById(task);
            return null;
        }
        task.setProcessingDeadline(processingDeadline(task));
        if (processingLimitReached(task, now)) {
            task.setStatus(SkillImportTaskStatusEnum.FAILED.name());
            task.setUpdateTime(now);
            taskMapper.updateById(task);
            return null;
        }
        if (task.getSourceKey() == null || task.getSourceSha256() == null || task.getSourceBytes() == null
                || task.getSourceBytes() <= 0 || task.getSourceBytes() > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES) {
            task.setStatus(SkillImportTaskStatusEnum.FAILED.name());
            task.setErrorCode("INVALID_SOURCE");
            task.setPublicReason("源包记录不完整，请重新创建导入");
            task.setUpdateTime(now);
            taskMapper.updateById(task);
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        task.setExecutionToken(token);
        task.setStatus(SkillImportTaskStatusEnum.RUNNING.name());
        task.setLeaseUntil(leaseDeadline(now, task.getProcessingDeadline()));
        task.setAttemptCount(task.getAttemptCount() + 1);
        task.setUpdateTime(now);
        taskMapper.updateById(task);
        return new SkillExecutionClaim(jobId, task.getDraftId(), task.getOwnerUserId(), token,
                task.getSourceKey(), task.getSourceSha256(), task.getSourceBytes());
    }

    /**
     * @param claim 原执行代次
     * @return 仍有资格执行；取消/过期不能被续租复活
     */
    public boolean renew(SkillExecutionClaim claim) {
        if (!accountAvailabilityService.isAccountActive(claim.userId())) {
            return false;
        }
        SkillDraftDO draft = draftMapper.selectById(claim.draftId());
        if (draft == null || !claim.jobId().equals(draft.getLatestJobId()) || !EDITABLE.contains(draft.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SkillImportTaskDO task = taskMapper.selectById(claim.jobId());
        if (task == null || !processingDeadline(task).isAfter(now)) {
            return false;
        }
        return taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getJobId, claim.jobId()).eq(SkillImportTaskDO::getExecutionToken, claim.token())
                .eq(SkillImportTaskDO::getStatus, SkillImportTaskStatusEnum.RUNNING.name())
                .gt(SkillImportTaskDO::getLeaseUntil, now)
                .and(condition -> condition.gt(SkillImportTaskDO::getProcessingDeadline, now)
                        .or().isNull(SkillImportTaskDO::getProcessingDeadline))
                .set(SkillImportTaskDO::getLeaseUntil, leaseDeadline(now, processingDeadline(task)))
                .set(SkillImportTaskDO::getUpdateTime, now)) == 1;
    }

    /**
     * @param claim 原执行代次
     * @param report 已校验清单及字节摘要的成功结果
     * @param archiveKey 本执行代次不可变且已写入的私有归档
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(SkillExecutionClaim claim, SkillValidationReport report, String archiveKey) {
        SkillOwnedDraft owned = accessPolicy.lockDraft(claim.userId(), claim.draftId());
        SkillImportTaskDO task = taskMapper.selectByIdForUpdate(claim.jobId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!claim.jobId().equals(owned.draft().getLatestJobId()) || !EDITABLE.contains(owned.draft().getStatus())
                || task == null || !SkillImportTaskStatusEnum.RUNNING.name().equals(task.getStatus())
                || !processingDeadline(task).isAfter(now)
                || !claim.token().equals(task.getExecutionToken())
                || task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now)) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        SkillArtifactDO artifact = new SkillArtifactDO();
        artifact.setArtifactId(SnowflakeIdUtil.nextId());
        artifact.setOwnerUserId(claim.userId());
        artifact.setArchiveKey(archiveKey);
        artifact.setArchiveSha256(report.archiveSha256());
        artifact.setContentSha256(report.contentSha256());
        artifact.setArchiveBytes((long) report.archive().length);
        artifact.setUnpackedBytes(report.unpackedBytes());
        artifact.setFileCount(report.files().size());
        artifact.setSkillMarkdown(report.skillMarkdown());
        try {
            artifact.setManifestJson(objectMapper.writeValueAsString(new SkillPackageManifest(report.packageName(),
                    report.packageDescription(), report.files())));
        } catch (JsonProcessingException exception) {
            throw new ServerException("Skill清单不能持久化", SkillErrorCodeEnum.UNAVAILABLE);
        }
        artifact.setStatus(SkillArtifactStatusEnum.READY.name());
        artifact.setCreateTime(now);
        artifact.setUpdateTime(now);
        artifactMapper.insert(artifact);
        for (SkillValidatedFile entry : report.files()) {
            SkillArtifactFileDO file = new SkillArtifactFileDO();
            file.setFileId(SnowflakeIdUtil.nextId());
            file.setArtifactId(artifact.getArtifactId());
            file.setRelativePath(entry.relativePath());
            file.setPathHash(DigestUtil.sha256Hex(entry.relativePath()));
            file.setSha256(entry.sha256());
            file.setSizeBytes(entry.sizeBytes());
            file.setCreateTime(now);
            file.setUpdateTime(now);
            fileMapper.insert(file);
        }
        SkillDraftDO draft = owned.draft();
        draft.setArtifactId(artifact.getArtifactId());
        draft.setStatus(SkillDraftStatusEnum.READY.name());
        draft.setRevision(draft.getRevision() + 1);
        draft.setUpdateTime(now);
        draftMapper.updateById(draft);
        task.setArtifactId(artifact.getArtifactId());
        task.setStatus(SkillImportTaskStatusEnum.SUCCEEDED.name());
        task.setUpdateTime(now);
        taskMapper.updateById(task);
    }

    /**
     * @param claim 原执行代次
     * @param rejected 内容拒绝不可重试
     * @param code 安全固定原因
     * @param reason 安全说明
     */
    @Transactional(rollbackFor = Exception.class)
    public void fail(SkillExecutionClaim claim, boolean rejected, String code, String reason) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SkillImportTaskDO task = taskMapper.selectByIdForUpdate(claim.jobId());
        if (task == null || !SkillImportTaskStatusEnum.RUNNING.name().equals(task.getStatus())
                || !claim.token().equals(task.getExecutionToken())
                || task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now)) {
            return;
        }
        task.setProcessingDeadline(processingDeadline(task));
        task.setErrorCode(code);
        task.setPublicReason(reason);
        boolean terminal = rejected || processingLimitReached(task, now);
        task.setStatus(terminal ? SkillImportTaskStatusEnum.FAILED.name()
                : SkillImportTaskStatusEnum.RETRY_WAIT.name());
        task.setNextRetryAt(now.plusSeconds((long) RETRY_SECONDS * task.getAttemptCount()));
        task.setUpdateTime(now);
        taskMapper.updateById(task);
    }

    /** 旧任务只按已有时间事实兼容，不能在认领或续租时重新开始计时。 */
    private LocalDateTime processingDeadline(SkillImportTaskDO task) {
        if (task.getProcessingDeadline() != null) {
            return task.getProcessingDeadline();
        }
        LocalDateTime start = SkillImportTaskStatusEnum.QUEUED.name().equals(task.getStatus())
                && task.getAttemptCount() == 0 ? task.getNextRetryAt() : task.getCreateTime();
        return start.plusMinutes(SkillPackageLimitConstant.PROCESSING_WINDOW_MINUTES);
    }

    private LocalDateTime leaseDeadline(LocalDateTime now, LocalDateTime deadline) {
        LocalDateTime lease = now.plusSeconds(LEASE_SECONDS);
        return lease.isBefore(deadline) ? lease : deadline;
    }

    private boolean processingLimitReached(SkillImportTaskDO task, LocalDateTime now) {
        if (!processingDeadline(task).isAfter(now)) {
            task.setErrorCode("CHECK_TIMEOUT");
            task.setPublicReason("检查处理超时，请重新选择ZIP文件导入");
            return true;
        }
        if (task.getAttemptCount() >= MAX_ATTEMPTS) {
            task.setErrorCode("CHECK_RETRY_EXHAUSTED");
            task.setPublicReason("检查依赖连续失败，已停止重试，请重新选择ZIP文件导入");
            return true;
        }
        return false;
    }
}
