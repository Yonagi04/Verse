package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillArtifactStatusEnum;
import com.yonagi.verse.common.enums.SkillDraftStatusEnum;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.enums.SkillStatusEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.dao.entity.skillhub.SkillSubmissionDO;
import com.yonagi.verse.dao.entity.skillhub.SkillApprovalDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillSubmissionMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillApprovalMapper;
import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;
import com.yonagi.verse.dto.req.SkillDraftSubmitReqDTO;
import com.yonagi.verse.dto.resp.SkillPublicationRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 提交用例：先恢复原回执，再检查草稿，审批及发布共同提交。 */
@Service
@RequiredArgsConstructor
public class SkillPublicationService {
    private static final String SUBMIT_COMMAND = "SUBMIT_DRAFT";
    private final SkillAccessPolicy accessPolicy;
    private final SkillArtifactMapper artifactMapper;
    private final SkillPublicationPolicy publicationPolicy;
    private final SkillPublicationSnapshotCodec snapshotCodec;
    private final SkillIdempotencyService idempotencyService;
    private final SkillPublicationWriter publicationWriter;
    private final SkillSubmissionMapper submissionMapper;
    private final SkillApprovalMapper approvalMapper;

    /**
     * @param userId 可信账号
     * @param submissionId 本人提交回执
     * @return 审批事实及当前公开状态，刷新或响应丢失后可恢复
     */
    public SkillPublicationRespDTO get(Long userId, Long submissionId) {
        SkillSubmissionDO submission = submissionMapper.selectOne(Wrappers.lambdaQuery(SkillSubmissionDO.class)
                .eq(SkillSubmissionDO::getSubmissionId, submissionId).eq(SkillSubmissionDO::getSubmittedBy, userId));
        if (submission == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        SkillOwnedDraft owned = accessPolicy.readDraft(userId, submission.getDraftId());
        SkillApprovalDO approval = approvalMapper.selectOne(Wrappers.lambdaQuery(SkillApprovalDO.class)
                .eq(SkillApprovalDO::getSubmissionId, submissionId));
        if (approval == null) {
            throw new com.yonagi.verse.common.convention.exception.ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        return new SkillPublicationRespDTO(submission.getSkillId().toString(), submissionId.toString(),
                approval.getApprovalId().toString(), submission.getVersionId().toString(), approval.getStatus(),
                approval.getDecisionSource(), SkillStatusEnum.ACTIVE.name().equals(owned.skill().getStatus())
                ? "PUBLISHED" : owned.skill().getStatus(), owned.publisher().getHandle(), owned.skill().getSlug());
    }

    /**
     * @param userId 可信会话账号
     * @param draftId 本人READY草稿
     * @param input 用户确认的修订号
     * @param key 结果未知时继续复用的UUID幂等键
     * @return 已提交原事务的发布回执；失败不留下通过审批的孤立版本
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillPublicationRespDTO submit(Long userId, Long draftId, SkillDraftSubmitReqDTO input, String key) {
        if (input == null || input.revision() == null || input.revision() < 0) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        SkillOwnedDraft owned = accessPolicy.lockDraft(userId, draftId);
        List<Long> request = List.of(draftId, input.revision());
        SkillPublicationRespDTO replay = idempotencyService.replay(userId, SUBMIT_COMMAND, key, request,
                SkillPublicationRespDTO.class);
        if (replay != null) {
            return replay;
        }
        SkillDraftDO draft = owned.draft();
        if (!SkillDraftStatusEnum.READY.name().equals(draft.getStatus()) || draft.getArtifactId() == null
                || !input.revision().equals(draft.getRevision()) || draft.getMetadataJson() == null
                || !SkillStatusEnum.DRAFT.name().equals(owned.skill().getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        SkillArtifactDO artifact = artifactMapper.selectByIdForUpdate(draft.getArtifactId());
        if (artifact == null || !userId.equals(artifact.getOwnerUserId())
                || !SkillArtifactStatusEnum.READY.name().equals(artifact.getStatus())
                || artifact.getArchiveBytes() > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES
                || artifact.getFileCount() > SkillPackageLimitConstant.MAX_FILE_COUNT) {
            throw new ClientException(SkillErrorCodeEnum.PACKAGE_INVALID);
        }
        SkillPublicationMetadataDTO metadata = snapshotCodec.decode(draft.getMetadataJson());
        publicationPolicy.validateSubmission(metadata);
        SkillPublicationRespDTO result = publicationWriter.publish(owned, artifact, metadata);
        idempotencyService.record(userId, SUBMIT_COMMAND, key, request, Long.valueOf(result.submissionId()), result);
        return result;
    }
}
