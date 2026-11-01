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
import java.util.List;
import com.fasterxml.jackson.core.type.TypeReference;
import com.yonagi.verse.common.constant.SkillGithubLimitConstant;
import com.yonagi.verse.dao.entity.skillhub.SkillGithubSourceDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillGithubSourceMapper;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import com.yonagi.verse.service.skillhub.port.SkillGithubSourceException;

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
    private final SkillGithubSourceMapper sourceMapper;
    private final SkillGithubSnapshotCodec githubCodec;
    private final SkillPublicationSnapshotCodec snapshotCodec;

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
        if ("GITHUB".equals(task.getSourceType()) && task.getProcessingDeadline() == null) {
            if (task.getQueueDeadline() == null || !task.getQueueDeadline().isAfter(now)) {
                task.setStatus("FAILED");
                task.setErrorCode("QUEUE_TIMEOUT");
                task.setPublicReason("排队超时，请稍后重新导入固定来源");
                task.setUpdateTime(now);
                taskMapper.updateById(task);
                return null;
            }
            int seconds = switch (task.getPhase()) {
                case "RESOLVE" -> 20;
                case "FETCH" -> 100;
                case "VALIDATE" -> 120;
                default -> 0;
            };
            task.setProcessingDeadline(now.plusSeconds(seconds));
        } else {
            task.setProcessingDeadline(processingDeadline(task));
        }
        if (processingLimitReached(task, now)) {
            task.setStatus(SkillImportTaskStatusEnum.FAILED.name());
            task.setUpdateTime(now);
            taskMapper.updateById(task);
            return null;
        }
        boolean github = "GITHUB".equals(task.getSourceType());
        boolean needsArchive = !github || "VALIDATE".equals(task.getPhase());
        long limit = github ? SkillGithubLimitConstant.MAX_ARCHIVE_BYTES : SkillPackageLimitConstant.MAX_ARCHIVE_BYTES;
        if (needsArchive && (task.getSourceKey() == null || task.getSourceSha256() == null || task.getSourceBytes() == null
                || task.getSourceBytes() <= 0 || task.getSourceBytes() > limit)) {
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
        task.setLeaseUntil(leaseDeadline(now, task.getProcessingDeadline(), task.getPhase()));
        task.setAttemptCount(task.getAttemptCount() + 1);
        task.setUpdateTime(now);
        taskMapper.updateById(task);
        return new SkillExecutionClaim(jobId, task.getDraftId(), task.getOwnerUserId(), token,
                task.getSourceKey(), task.getSourceSha256(), task.getSourceBytes() == null ? 0L : task.getSourceBytes(),
                github ? "GITHUB" : "ZIP", task.getPhase(), task.getProcessingDeadline().toInstant(ZoneOffset.UTC));
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
                .eq(SkillImportTaskDO::getPhase, task.getPhase())
                .gt(SkillImportTaskDO::getLeaseUntil, now)
                .and(condition -> condition.gt(SkillImportTaskDO::getProcessingDeadline, now)
                        .or().isNull(SkillImportTaskDO::getProcessingDeadline))
                .set(SkillImportTaskDO::getLeaseUntil, leaseDeadline(now, processingDeadline(task), task.getPhase()))
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
        if ("GITHUB".equals(task.getSourceType())) {
            if (!Set.of("FETCH", "VALIDATE").contains(task.getPhase()) || !task.getPhase().equals(claim.phase())) {
                throw new ClientException(SkillErrorCodeEnum.CONFLICT);
            }
            SkillGithubSourceDO stored = sourceMapper.selectById(claim.jobId());
            SkillGithubSourceOptionDTO confirmed = githubCodec.decode(stored.getConfirmedSourceJson(),
                    SkillGithubSourceOptionDTO.class);
            List<SkillGithubCandidateDTO> candidates = githubCodec.list(stored.getCandidatesJson(),
                    new TypeReference<List<SkillGithubCandidateDTO>>() { });
            SkillGithubCandidateDTO candidate = candidates.stream()
                    .filter(value -> value.candidateId().equals(stored.getSelectedCandidateId()))
                    .findFirst().orElseThrow(() -> new ClientException(SkillErrorCodeEnum.CONFLICT));
            if (("FETCH".equals(task.getPhase()) && candidates.size() != 1)
                    || !Boolean.TRUE.equals(candidate.selectable()) || confirmed == null) {
                throw new ClientException(SkillErrorCodeEnum.CONFLICT);
            }
            SkillSourceDTO source = confirmed.source();
            artifact.setSourceType("GITHUB");
            artifact.setSourceJson(githubCodec.encode(new SkillSourceDTO(1, "GITHUB", source.repositoryUrl(),
                    source.repositoryId(), source.requestedRef(), source.resolvedRefType(), source.commitSha(),
                    candidate.directory()), 8192));
        } else {
            artifact.setSourceType("ZIP");
        }
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
        draft.setMetadataJson(snapshotCodec.clearConfirmations(draft.getMetadataJson()));
        draft.setStatus(SkillDraftStatusEnum.READY.name());
        draft.setRevision(draft.getRevision() + 1);
        draft.setUpdateTime(now);
        draftMapper.updateById(draft);
        task.setArtifactId(artifact.getArtifactId());
        task.setStatus(SkillImportTaskStatusEnum.SUCCEEDED.name());
        task.setPhase("VALIDATE");
        task.setUpdateTime(now);
        taskMapper.updateById(task);
        clearErrors(task);
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

    /** 将完整的来源解析快照持久化后暂停，网络响应丢失不影响刷新恢复。 */
    @Transactional(rollbackFor = Exception.class)
    public void resolved(SkillExecutionClaim claim, List<SkillGithubSourceOptionDTO> options) {
        SkillOwnedDraft owned = accessPolicy.lockDraft(claim.userId(), claim.draftId());
        SkillImportTaskDO task = requireCurrent(claim, owned);
        if (!"RESOLVE".equals(task.getPhase()) || options.isEmpty() || options.size() > 20) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        SkillGithubSourceDO source = sourceMapper.selectById(claim.jobId());
        source.setSourceOptionsJson(githubCodec.encode(options, 32768));
        source.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        sourceMapper.updateById(source);
        pause(task, "WAITING_CONFIRMATION", "RESOLVE");
        taskMapper.updateById(task);
        clearErrors(task);
    }

    /** @return true表示唯一候选可在原FETCH代次内完整检查，false表示已暂停等待用户选择 */
    @Transactional(rollbackFor = Exception.class)
    public boolean fetched(SkillExecutionClaim claim, String sourceKey, String sourceSha, long sourceBytes,
                            String treeKey, String treeSha, String scanDigest, List<SkillGithubCandidateDTO> candidates) {
        SkillOwnedDraft owned = accessPolicy.lockDraft(claim.userId(), claim.draftId());
        SkillImportTaskDO task = requireCurrent(claim, owned);
        if (!"FETCH".equals(task.getPhase()) || candidates.isEmpty() || candidates.size() > 100) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        clearErrors(task);
        SkillGithubSourceDO source = sourceMapper.selectById(claim.jobId());
        source.setTreeKey(treeKey);
        source.setTreeSha256(treeSha);
        source.setScanDigest(scanDigest);
        source.setCandidatesJson(githubCodec.encode(candidates, 256 * 1024));
        source.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        task.setSourceKey(sourceKey);
        task.setSourceSha256(sourceSha);
        task.setSourceBytes(sourceBytes);
        boolean automatic = candidates.size() == 1 && Boolean.TRUE.equals(candidates.getFirst().selectable());
        if (automatic) {
            source.setSelectedCandidateId(candidates.getFirst().candidateId());
        } else if (candidates.size() == 1) {
            source.setValidationIssuesJson(githubCodec.encode(candidates.getFirst().issues(), 64 * 1024));
            task.setStatus("FAILED");
            task.setErrorCode(candidates.getFirst().issues().getFirst().code());
            task.setPublicReason(candidates.getFirst().issues().getFirst().message());
        } else {
            pause(task, "WAITING_SELECTION", "SELECT");
            owned.draft().setRevision(owned.draft().getRevision() + 1);
            owned.draft().setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
            draftMapper.updateById(owned.draft());
        }
        task.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        sourceMapper.updateById(source);
        taskMapper.updateById(task);
        return automatic;
    }

    /** 外部边界的已归一化拒绝/限流；文件问题与状态共同提交。 */
    @Transactional(rollbackFor = Exception.class)
    public void githubFailed(SkillExecutionClaim claim, SkillGithubSourceException failure) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SkillImportTaskDO task = taskMapper.selectByIdForUpdate(claim.jobId());
        if (task == null || !"RUNNING".equals(task.getStatus()) || !claim.token().equals(task.getExecutionToken())
                || !claim.phase().equals(task.getPhase()) || task.getLeaseUntil() == null
                || !task.getLeaseUntil().isAfter(now)) {
            return;
        }
        LocalDateTime retry = failure.retryAt() == null
                ? now.plusSeconds(task.getAttemptCount() == 1 ? 1 : 3)
                : LocalDateTime.ofInstant(failure.retryAt(), ZoneOffset.UTC);
        boolean canRetry = failure.retryable() && task.getAttemptCount() < MAX_ATTEMPTS
                && retry.isBefore(task.getProcessingDeadline());
        task.setStatus(canRetry ? "RETRY_WAIT" : "FAILED");
        task.setErrorCode(failure.code());
        task.setPublicReason(failure.getMessage());
        task.setNextRetryAt(retry);
        task.setUpdateTime(now);
        SkillGithubSourceDO source = sourceMapper.selectById(task.getJobId());
        if (source != null) {
            source.setValidationIssuesJson(githubCodec.encode(failure.issues(), 64 * 1024));
            source.setUpdateTime(now);
            sourceMapper.updateById(source);
        }
        taskMapper.updateById(task);
    }

    private SkillImportTaskDO requireCurrent(SkillExecutionClaim claim, SkillOwnedDraft owned) {
        SkillImportTaskDO task = taskMapper.selectByIdForUpdate(claim.jobId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (task == null || !claim.jobId().equals(owned.draft().getLatestJobId())
                || !EDITABLE.contains(owned.draft().getStatus()) || !"RUNNING".equals(task.getStatus())
                || !claim.token().equals(task.getExecutionToken()) || !claim.phase().equals(task.getPhase())
                || task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now)
                || task.getProcessingDeadline() == null || !task.getProcessingDeadline().isAfter(now)) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        return task;
    }

    private void pause(SkillImportTaskDO task, String status, String phase) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        task.setStatus(status);
        task.setPhase(phase);
        task.setWaitUntil(now.plusDays(SkillGithubLimitConstant.WAIT_DAYS));
        task.setLeaseUntil(null);
        task.setUpdateTime(now);
    }

    private void clearErrors(SkillImportTaskDO task) {
        task.setErrorCode(null);
        task.setPublicReason(null);
        taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getJobId, task.getJobId()).set(SkillImportTaskDO::getErrorCode, null)
                .set(SkillImportTaskDO::getPublicReason, null));
        if ("GITHUB".equals(task.getSourceType())) {
            sourceMapper.update(null, Wrappers.lambdaUpdate(SkillGithubSourceDO.class)
                    .eq(SkillGithubSourceDO::getJobId, task.getJobId())
                    .set(SkillGithubSourceDO::getValidationIssuesJson, null));
        }
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

    private LocalDateTime leaseDeadline(LocalDateTime now, LocalDateTime deadline, String phase) {
        LocalDateTime lease = now.plusSeconds("RESOLVE".equals(phase) ? 5 : LEASE_SECONDS);
        return lease.isBefore(deadline) ? lease : deadline;
    }

    private boolean processingLimitReached(SkillImportTaskDO task, LocalDateTime now) {
        if (!processingDeadline(task).isAfter(now)) {
            task.setErrorCode("GITHUB".equals(task.getSourceType()) ? "SOURCE_TIMEOUT" : "CHECK_TIMEOUT");
            task.setPublicReason("GITHUB".equals(task.getSourceType())
                    ? "处理超时，请稍后重新导入此固定提交" : "检查处理超时，请重新选择ZIP文件导入");
            return true;
        }
        if (task.getAttemptCount() >= MAX_ATTEMPTS) {
            task.setErrorCode("CHECK_RETRY_EXHAUSTED");
            task.setPublicReason("GITHUB".equals(task.getSourceType())
                    ? "导入依赖连续失败，请稍后重试此固定提交" : "检查依赖连续失败，已停止重试，请重新选择ZIP文件导入");
            return true;
        }
        return false;
    }
}
