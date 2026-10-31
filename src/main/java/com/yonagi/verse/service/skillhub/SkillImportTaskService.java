package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillDraftStatusEnum;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.enums.SkillImportTaskStatusEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dto.req.SkillImportCreateReqDTO;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 导入命令的短事务；网络上传、解析均由事务外编排。 */
@Service
@RequiredArgsConstructor
public class SkillImportTaskService {
    private static final int IMPORT_WINDOW_DAYS = 1;
    private static final String CREATE_COMMAND = "CREATE_IMPORT";
    private static final int MAX_ACTIVE_TASKS = 2;
    private static final int MAX_DAILY_TASKS = 20;
    private static final int UPLOAD_LEASE_MINUTES = 2;
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private final SkillAccessPolicy accessPolicy;
    private final SkillImportTaskMapper taskMapper;
    private final SkillDraftMapper draftMapper;
    private final SkillIdempotencyService idempotencyService;

    /**
     * @param userId 可信账号
     * @param draftId 本人可编辑草稿
     * @param input 当前草稿修订
     * @param key 创建命令UUID
     * @return 持久化任务，原命令重放不重复分配
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillImportTaskRespDTO create(Long userId, Long draftId, SkillImportCreateReqDTO input, String key) {
        if (input == null || input.revision() == null || input.revision() < 0) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        SkillOwnedDraft owned = accessPolicy.lockDraft(userId, draftId);
        List<Long> request = List.of(draftId, input.revision());
        Long replay = idempotencyService.replay(userId, CREATE_COMMAND, key, request, Long.class);
        if (replay != null) {
            return view(requireOwnedTask(userId, replay), owned.draft());
        }
        requireEditable(owned.draft());
        if (!input.revision().equals(owned.draft().getRevision())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long active = taskMapper.selectCount(Wrappers.lambdaQuery(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getOwnerUserId, userId).notIn(SkillImportTaskDO::getStatus, TERMINAL));
        Long daily = taskMapper.selectCount(Wrappers.lambdaQuery(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getOwnerUserId, userId)
                .ge(SkillImportTaskDO::getCreateTime, now.minusDays(IMPORT_WINDOW_DAYS)));
        if (active >= MAX_ACTIVE_TASKS || daily >= MAX_DAILY_TASKS) {
            throw new ClientException(SkillErrorCodeEnum.RATE_LIMITED);
        }
        cancelPrevious(owned.draft(), now);
        SkillImportTaskDO task = new SkillImportTaskDO();
        task.setJobId(SnowflakeIdUtil.nextId());
        task.setDraftId(draftId);
        task.setOwnerUserId(userId);
        task.setStatus(SkillImportTaskStatusEnum.WAITING_UPLOAD.name());
        task.setAttemptCount(0);
        task.setNextRetryAt(now);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        taskMapper.insert(task);
        SkillDraftDO draft = owned.draft();
        draft.setLatestJobId(task.getJobId());
        draft.setArtifactId(null);
        draft.setStatus(SkillDraftStatusEnum.EDITING.name());
        draft.setRevision(draft.getRevision() + 1);
        draft.setUpdateTime(now);
        draftMapper.update(null, Wrappers.lambdaUpdate(SkillDraftDO.class).eq(SkillDraftDO::getDraftId, draftId)
                .set(SkillDraftDO::getLatestJobId, task.getJobId()).set(SkillDraftDO::getArtifactId, null)
                .set(SkillDraftDO::getStatus, draft.getStatus()).set(SkillDraftDO::getRevision, draft.getRevision())
                .set(SkillDraftDO::getUpdateTime, now));
        idempotencyService.record(userId, CREATE_COMMAND, key, request, task.getJobId(), task.getJobId());
        return view(task, draft);
    }

    /**
     * @param userId 可信账号
     * @param jobId 本人任务
     * @return 安全状态
     */
    public SkillImportTaskRespDTO get(Long userId, Long jobId) {
        SkillImportTaskDO task = requireOwnedTask(userId, jobId);
        SkillOwnedDraft owned = accessPolicy.readDraft(userId, task.getDraftId());
        return view(task, owned.draft());
    }

    /**
     * @param userId 可信账号
     * @param jobId 本人等待上传任务
     * @return 分代的不可变私有Key
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillUploadClaim beginUpload(Long userId, Long jobId) {
        SkillImportTaskDO reference = requireOwnedTask(userId, jobId);
        SkillOwnedDraft owned = accessPolicy.lockDraft(userId, reference.getDraftId());
        requireEditable(owned.draft());
        SkillImportTaskDO task = taskMapper.selectByIdForUpdate(jobId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean expiredUpload = SkillImportTaskStatusEnum.UPLOADING.name().equals(task.getStatus())
                && task.getUploadUntil() != null && !task.getUploadUntil().isAfter(now);
        if (!jobId.equals(owned.draft().getLatestJobId())
                || (!SkillImportTaskStatusEnum.WAITING_UPLOAD.name().equals(task.getStatus()) && !expiredUpload)) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        String sourceKey = "skillhub/v1/source/" + jobId + "/" + token + ".zip";
        task.setUploadToken(token);
        task.setSourceKey(sourceKey);
        task.setUploadUntil(now.plusMinutes(UPLOAD_LEASE_MINUTES));
        task.setStatus(SkillImportTaskStatusEnum.UPLOADING.name());
        task.setUpdateTime(now);
        taskMapper.updateById(task);
        return new SkillUploadClaim(jobId, task.getDraftId(), userId, token, sourceKey);
    }

    /**
     * @param claim 当前上传代次
     * @param sha256 已实际上传字节摘要
     * @param bytes 实际大小
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeUpload(SkillUploadClaim claim, String sha256, long bytes) {
        SkillOwnedDraft owned = accessPolicy.lockDraft(claim.userId(), claim.draftId());
        requireEditable(owned.draft());
        SkillImportTaskDO task = taskMapper.selectByIdForUpdate(claim.jobId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!claim.jobId().equals(owned.draft().getLatestJobId())
                || !SkillImportTaskStatusEnum.UPLOADING.name().equals(task.getStatus())
                || !claim.token().equals(task.getUploadToken()) || !task.getUploadUntil().isAfter(now)) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        task.setSourceSha256(sha256);
        task.setSourceBytes(bytes);
        task.setProcessingDeadline(now.plusMinutes(SkillPackageLimitConstant.PROCESSING_WINDOW_MINUTES));
        task.setStatus(SkillImportTaskStatusEnum.QUEUED.name());
        task.setNextRetryAt(now);
        task.setUpdateTime(now);
        taskMapper.updateById(task);
    }

    /**
     * @param claim 失败上传代次；仅重置仍属于本代次的状态，不覆盖取消或新上传
     */
    public void uploadFailed(SkillUploadClaim claim) {
        taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getJobId, claim.jobId()).eq(SkillImportTaskDO::getOwnerUserId, claim.userId())
                .eq(SkillImportTaskDO::getUploadToken, claim.token())
                .eq(SkillImportTaskDO::getStatus, SkillImportTaskStatusEnum.UPLOADING.name())
                .set(SkillImportTaskDO::getStatus, SkillImportTaskStatusEnum.WAITING_UPLOAD.name())
                .set(SkillImportTaskDO::getUpdateTime, LocalDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * @param userId 可信账号
     * @param jobId 本人任务；终态重复取消保持原结果
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long jobId) {
        SkillImportTaskDO reference = requireOwnedTask(userId, jobId);
        accessPolicy.lockDraft(userId, reference.getDraftId());
        SkillImportTaskDO task = taskMapper.selectByIdForUpdate(jobId);
        if (!TERMINAL.contains(task.getStatus())) {
            task.setStatus(SkillImportTaskStatusEnum.CANCELLED.name());
            task.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(task);
        }
    }

    private SkillImportTaskDO requireOwnedTask(Long userId, Long jobId) {
        SkillImportTaskDO task = taskMapper.selectOne(Wrappers.lambdaQuery(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getJobId, jobId).eq(SkillImportTaskDO::getOwnerUserId, userId));
        if (task == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return task;
    }

    private void requireEditable(SkillDraftDO draft) {
        if (!Set.of(SkillDraftStatusEnum.EDITING.name(), SkillDraftStatusEnum.READY.name())
                .contains(draft.getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
    }

    private void cancelPrevious(SkillDraftDO draft, LocalDateTime now) {
        if (draft.getLatestJobId() != null) {
            taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                    .eq(SkillImportTaskDO::getJobId, draft.getLatestJobId())
                    .notIn(SkillImportTaskDO::getStatus, TERMINAL)
                    .set(SkillImportTaskDO::getStatus, SkillImportTaskStatusEnum.CANCELLED.name())
                    .set(SkillImportTaskDO::getUpdateTime, now));
        }
    }

    private SkillImportTaskRespDTO view(SkillImportTaskDO task, SkillDraftDO draft) {
        return new SkillImportTaskRespDTO(task.getJobId().toString(), task.getDraftId().toString(), task.getStatus(),
                draft.getRevision(), task.getErrorCode(), task.getPublicReason(), task.getAttemptCount(),
                task.getUpdateTime().atOffset(ZoneOffset.UTC));
    }
}
