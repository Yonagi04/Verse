package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.yonagi.verse.common.constant.SkillGithubLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillGithubSourceDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillGithubSourceMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.req.SkillGithubImportCreateReqDTO;
import com.yonagi.verse.dto.req.SkillGithubSelectionReqDTO;
import com.yonagi.verse.dto.req.SkillGithubSourceConfirmReqDTO;
import com.yonagi.verse.dto.resp.SkillGithubCandidatesRespDTO;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GitHub创建、来源确认与目录选择的短事务，不执行远程HTTP或包解析。 */
@Service
@RequiredArgsConstructor
public class SkillGithubImportService {
    private final SkillAccessPolicy access;
    private final SkillImportTaskService tasks;
    private final SkillImportTaskMapper taskMapper;
    private final SkillGithubSourceMapper sourceMapper;
    private final SkillDraftMapper draftMapper;
    private final SkillGithubSourcePolicy policy;
    private final SkillGithubSnapshotCodec codec;
    private final SkillIdempotencyService commands;
    private final Validator validator;

    /** @return 可恢复解析任务；请求重放不重新创建源记录或重新读取分支 */
    @Transactional(rollbackFor = Exception.class)
    public SkillImportTaskRespDTO create(Long userId, Long draftId, SkillGithubImportCreateReqDTO input, String key) {
        validate(input);
        SkillGithubSourceRequest request = policy.parse(input);
        Long jobId = tasks.createGithub(userId, draftId, input.revision(), key, request);
        if (sourceMapper.selectById(jobId) == null) {
            SkillGithubSourceDO source = new SkillGithubSourceDO();
            source.setJobId(jobId);
            source.setRequestJson(codec.encode(request, 8192));
            source.setCreateTime(now());
            source.setUpdateTime(now());
            sourceMapper.insert(source);
        }
        return tasks.get(userId, jobId);
    }

    /** @return 冻结的完整来源；客户端不提供可绕过服务端解析的SHA */
    @Transactional(rollbackFor = Exception.class)
    public SkillImportTaskRespDTO confirm(Long userId, Long jobId, SkillGithubSourceConfirmReqDTO input, String key) {
        validate(input);
        SkillImportTaskDO task = owned(userId, jobId);
        SkillOwnedDraft owned = access.lockDraft(userId, task.getDraftId());
        String command = "CONFIRM_GITHUB_SOURCE";
        List<Object> payload = List.of(jobId, input);
        Long replay = commands.replay(userId, command, key, payload, Long.class);
        if (replay != null) {
            return tasks.get(userId, replay);
        }
        task = taskMapper.selectByIdForUpdate(jobId);
        requireWaiting(task, owned, input.revision(), "WAITING_CONFIRMATION");
        SkillGithubSourceDO source = sourceMapper.selectById(jobId);
        SkillGithubSourceOptionDTO choice = codec.list(source.getSourceOptionsJson(),
                        new TypeReference<List<SkillGithubSourceOptionDTO>>() { }).stream()
                .filter(option -> option.sourceOptionId().equals(input.sourceOptionId())).findFirst()
                .orElseThrow(() -> new ClientException(SkillErrorCodeEnum.INVALID_INPUT));
        source.setConfirmedSourceJson(codec.encode(choice, 8192));
        source.setUpdateTime(now());
        sourceMapper.updateById(source);
        queue(task, owned, "FETCH");
        commands.record(userId, command, key, payload, jobId, jobId);
        return tasks.get(userId, jobId);
    }

    /** @return 当前任务快照分页，不重新访问GitHub */
    public SkillGithubCandidatesRespDTO candidates(Long userId, Long jobId, int page, int size) {
        SkillImportTaskDO task = owned(userId, jobId);
        access.readDraft(userId, task.getDraftId());
        if (page < 1 || page > 100 || !Set.of(20, 50).contains(size)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        SkillGithubSourceDO source = sourceMapper.selectById(jobId);
        List<SkillGithubCandidateDTO> values = source == null ? List.of()
                : codec.list(source.getCandidatesJson(), new TypeReference<List<SkillGithubCandidateDTO>>() { });
        int from = Math.min(values.size(), (page - 1) * size);
        return new SkillGithubCandidatesRespDTO(page, size, values.size(),
                List.copyOf(values.subList(from, Math.min(values.size(), from + size))),
                source == null ? null : source.getScanDigest());
    }

    /** @return 当前扫描中唯一选定的目录进入检查；旧扫描/旧修订不产生副作用 */
    @Transactional(rollbackFor = Exception.class)
    public SkillImportTaskRespDTO select(Long userId, Long jobId, SkillGithubSelectionReqDTO input, String key) {
        validate(input);
        SkillImportTaskDO task = owned(userId, jobId);
        SkillOwnedDraft owned = access.lockDraft(userId, task.getDraftId());
        String command = "SELECT_GITHUB_SKILL";
        List<Object> payload = List.of(jobId, input);
        Long replay = commands.replay(userId, command, key, payload, Long.class);
        if (replay != null) {
            return tasks.get(userId, replay);
        }
        task = taskMapper.selectByIdForUpdate(jobId);
        requireWaiting(task, owned, input.revision(), "WAITING_SELECTION");
        SkillGithubSourceDO source = sourceMapper.selectById(jobId);
        if (!input.scanDigest().equals(source.getScanDigest())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        SkillGithubCandidateDTO selected = codec.list(source.getCandidatesJson(),
                        new TypeReference<List<SkillGithubCandidateDTO>>() { }).stream()
                .filter(value -> value.candidateId().equals(input.candidateId()) && Boolean.TRUE.equals(value.selectable()))
                .findFirst().orElseThrow(() -> new ClientException(SkillErrorCodeEnum.INVALID_INPUT));
        source.setSelectedCandidateId(selected.candidateId());
        source.setUpdateTime(now());
        sourceMapper.updateById(source);
        queue(task, owned, "VALIDATE");
        commands.record(userId, command, key, payload, jobId, jobId);
        return tasks.get(userId, jobId);
    }

    private void queue(SkillImportTaskDO task, SkillOwnedDraft owned, String phase) {
        LocalDateTime time = now();
        taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getJobId, task.getJobId()).set(SkillImportTaskDO::getStatus, "QUEUED")
                .set(SkillImportTaskDO::getPhase, phase).set(SkillImportTaskDO::getAttemptCount, 0)
                .set(SkillImportTaskDO::getQueueDeadline, time.plusMinutes(SkillGithubLimitConstant.QUEUE_MINUTES))
                .set(SkillImportTaskDO::getProcessingDeadline, null).set(SkillImportTaskDO::getLeaseUntil, null)
                .set(SkillImportTaskDO::getExecutionToken, null).set(SkillImportTaskDO::getWaitUntil, null)
                .set(SkillImportTaskDO::getErrorCode, null).set(SkillImportTaskDO::getPublicReason, null)
                .set(SkillImportTaskDO::getNextRetryAt, time).set(SkillImportTaskDO::getUpdateTime, time));
        owned.draft().setRevision(owned.draft().getRevision() + 1);
        owned.draft().setUpdateTime(time);
        draftMapper.updateById(owned.draft());
    }

    private void requireWaiting(SkillImportTaskDO task, SkillOwnedDraft owned, Long revision, String status) {
        if (!status.equals(task.getStatus()) || !"GITHUB".equals(task.getSourceType())
                || !task.getJobId().equals(owned.draft().getLatestJobId())
                || !"EDITING".equals(owned.draft().getStatus()) || !revision.equals(owned.draft().getRevision())
                || task.getWaitUntil() == null || !task.getWaitUntil().isAfter(now())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
    }

    private SkillImportTaskDO owned(Long userId, Long jobId) {
        SkillImportTaskDO task = taskMapper.selectOne(Wrappers.lambdaQuery(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getJobId, jobId).eq(SkillImportTaskDO::getOwnerUserId, userId)
                .eq(SkillImportTaskDO::getSourceType, "GITHUB"));
        if (task == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return task;
    }

    private void validate(Object input) {
        if (input == null || !validator.validate(input).isEmpty()) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
