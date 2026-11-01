package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillGithubSourceDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillGithubSourceMapper;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import com.yonagi.verse.dto.skillhub.SkillValidationIssueDTO;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import java.time.ZoneOffset;
import java.util.List;

import com.yonagi.verse.dto.skillhub.SkillGithubInputDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 本人任务响应映射；调用者必须先执行资源归属校验。 */
@Component
@RequiredArgsConstructor
public class SkillImportTaskViews {
    private final SkillGithubSourceMapper sourceMapper;
    private final SkillGithubSnapshotCodec codec;

    /** @return 不暴露对象Key、执行令牌或原始GitHub响应的任务视图 */
    public SkillImportTaskRespDTO view(SkillImportTaskDO task, SkillDraftDO draft) {
        if (!"GITHUB".equals(task.getSourceType())) {
            return new SkillImportTaskRespDTO(task.getJobId().toString(), task.getDraftId().toString(), task.getStatus(),
                    draft.getRevision(), task.getErrorCode(), task.getPublicReason(), task.getAttemptCount(),
                    task.getUpdateTime().atOffset(ZoneOffset.UTC));
        }
        SkillGithubSourceDO stored = sourceMapper.selectById(task.getJobId());
        List<SkillGithubSourceOptionDTO> options = stored == null ? List.of()
                : codec.list(stored.getSourceOptionsJson(), new TypeReference<List<SkillGithubSourceOptionDTO>>() { });
        SkillGithubSourceOptionDTO confirmed = stored == null ? null
                : codec.decode(stored.getConfirmedSourceJson(), SkillGithubSourceOptionDTO.class);
        SkillSourceDTO source = confirmed == null ? null : confirmed.source();
        List<SkillGithubCandidateDTO> candidates = stored == null ? List.of()
                : codec.list(stored.getCandidatesJson(), new TypeReference<List<SkillGithubCandidateDTO>>() { });
        if (source != null && stored.getSelectedCandidateId() != null) {
            String id = stored.getSelectedCandidateId();
            SkillGithubCandidateDTO selected = candidates.stream().filter(value -> id.equals(value.candidateId()))
                    .findFirst().orElse(null);
            if (selected != null) {
                source = new SkillSourceDTO(source.schemaVersion(), source.type(), source.repositoryUrl(),
                        source.repositoryId(), source.requestedRef(), source.resolvedRefType(), source.commitSha(),
                        selected.directory());
            }
        }
        List<SkillValidationIssueDTO> issues = stored == null ? List.of()
                : codec.list(stored.getValidationIssuesJson(), new TypeReference<List<SkillValidationIssueDTO>>() { });
        return new SkillImportTaskRespDTO(task.getJobId().toString(), task.getDraftId().toString(), task.getStatus(),
                draft.getRevision(), task.getErrorCode(), task.getPublicReason(), task.getAttemptCount(),
                task.getUpdateTime().atOffset(ZoneOffset.UTC), "GITHUB", task.getPhase(),
                "WAITING_CONFIRMATION".equals(task.getStatus()) ? options : List.of(), source, candidates.size(),
                stored == null ? null : stored.getScanDigest(),
                task.getWaitUntil() == null ? null : task.getWaitUntil().atOffset(ZoneOffset.UTC),
                "FAILED".equals(task.getStatus()),
                task.getErrorCode() == null || task.getNextRetryAt() == null ? null
                        : task.getNextRetryAt().atOffset(ZoneOffset.UTC), issues, requested(stored));
    }

    private SkillGithubInputDTO requested(SkillGithubSourceDO source) {
        SkillGithubSourceRequest input = source == null ? null
                : codec.decode(source.getRequestJson(), SkillGithubSourceRequest.class);
        if (input == null) {
            return null;
        }
        return new SkillGithubInputDTO(input.repositoryUrl()
                + (input.treeTail().isEmpty() ? "" : "/tree/" + input.treeTail()),
                input.refType(), input.ref(), input.directory());
    }
}
