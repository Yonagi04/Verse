package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillApprovalStatusEnum;
import com.yonagi.verse.common.enums.SkillDraftStatusEnum;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.enums.SkillStatusEnum;
import com.yonagi.verse.common.enums.SkillVersionStatusEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillApprovalDO;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillAuditRecordDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDisplayRevisionDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillSubmissionDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillAuditRecordMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDisplayRevisionMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillSubmissionMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.SkillPublicationMetadataDTO;
import com.yonagi.verse.dto.resp.SkillPublicationRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 单事务写入冻结修订、审批、版本及公开指针，不做文件或网络操作。 */
@Component
@RequiredArgsConstructor
public class SkillPublicationWriter {
    private static final String SUBMITTED = "SUBMITTED";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String PUBLISH_ACTION = "PUBLISH";
    private final SkillDisplayRevisionMapper revisionMapper;
    private final SkillSubmissionMapper submissionMapper;
    private final SkillApprovalService approvalService;
    private final SkillVersionMapper versionMapper;
    private final SkillMapper skillMapper;
    private final SkillDraftMapper draftMapper;
    private final SkillAuditRecordMapper auditMapper;

    /**
     * @param owned 已验证归属且持统一锁序的草稿
     * @param artifact 已锁定且READY的同账号制品
     * @param metadata 与草稿冻结JSON对应且通过提交校验的声明
     * @return 原事务内发布结果，任一步失败由外层回滚全部事实
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SkillPublicationRespDTO publish(SkillOwnedDraft owned, SkillArtifactDO artifact,
                                           SkillPublicationMetadataDTO metadata) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SkillDraftDO draft = owned.draft();
        SkillDO skill = owned.skill();
        SkillDisplayRevisionDO display = new SkillDisplayRevisionDO();
        display.setRevisionId(SnowflakeIdUtil.nextId());
        display.setSkillId(skill.getSkillId());
        display.setName(metadata.name());
        display.setSummary(metadata.summary());
        display.setCategoryId(metadata.categoryId());
        display.setCreateTime(now);
        display.setUpdateTime(now);
        revisionMapper.insert(display);
        for (String tag : metadata.tags()) {
            revisionMapper.insertTag(display.getRevisionId(), tag);
        }

        String snapshotHash = DigestUtil.sha256Hex(draft.getMetadataJson() + "\n" + artifact.getContentSha256());
        SkillSubmissionDO submission = new SkillSubmissionDO();
        submission.setSubmissionId(SnowflakeIdUtil.nextId());
        submission.setSkillId(skill.getSkillId());
        submission.setDraftId(draft.getDraftId());
        submission.setSubmittedBy(draft.getOwnerUserId());
        submission.setDisplayRevisionId(display.getRevisionId());
        submission.setArtifactId(artifact.getArtifactId());
        submission.setVersionLabel(metadata.version());
        submission.setVersionId(SnowflakeIdUtil.nextId());
        submission.setSnapshotHash(snapshotHash);
        submission.setStatus(SUBMITTED);
        submission.setCreateTime(now);
        submission.setUpdateTime(now);
        submissionMapper.insert(submission);
        SkillApprovalDO approval = approvalService.createAndApprove(submission.getSubmissionId(), snapshotHash);
        if (!SkillApprovalStatusEnum.APPROVED.name().equals(approval.getStatus())
                || !snapshotHash.equals(approval.getRequestSnapshotHash())
                || !submission.getSubmissionId().equals(approval.getSubmissionId())) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }

        SkillVersionDO version = new SkillVersionDO();
        version.setVersionId(submission.getVersionId());
        version.setSkillId(skill.getSkillId());
        version.setVersionLabel(metadata.version());
        version.setArtifactId(artifact.getArtifactId());
        version.setApprovedSubmissionId(submission.getSubmissionId());
        version.setStatus(SkillVersionStatusEnum.AVAILABLE.name());
        version.setReleaseNotes(metadata.releaseNotes());
        version.setDeclarationsJson(draft.getMetadataJson());
        version.setPublishedAt(now);
        version.setCreateTime(now);
        version.setUpdateTime(now);
        versionMapper.insert(version);
        int changed = skillMapper.update(null, Wrappers.lambdaUpdate(SkillDO.class)
                .eq(SkillDO::getSkillId, skill.getSkillId()).eq(SkillDO::getRevision, skill.getRevision())
                .eq(SkillDO::getStatus, SkillStatusEnum.DRAFT.name())
                .set(SkillDO::getStatus, SkillStatusEnum.ACTIVE.name())
                .set(SkillDO::getCurrentVersionId, version.getVersionId())
                .set(SkillDO::getPublicRevisionId, display.getRevisionId()).set(SkillDO::getLatestReleaseAt, now)
                .set(SkillDO::getRevision, skill.getRevision() + 1).set(SkillDO::getUpdateTime, now));
        int consumed = draftMapper.update(null, Wrappers.lambdaUpdate(SkillDraftDO.class)
                .eq(SkillDraftDO::getDraftId, draft.getDraftId()).eq(SkillDraftDO::getRevision, draft.getRevision())
                .eq(SkillDraftDO::getStatus, SkillDraftStatusEnum.READY.name())
                .set(SkillDraftDO::getStatus, SkillDraftStatusEnum.SUBMITTED.name())
                .set(SkillDraftDO::getRevision, draft.getRevision() + 1).set(SkillDraftDO::getUpdateTime, now));
        if (changed != 1 || consumed != 1) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        submission.setStatus(SkillApprovalStatusEnum.APPROVED.name());
        if (submissionMapper.updateById(submission) != 1) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        SkillAuditRecordDO audit = new SkillAuditRecordDO();
        audit.setAuditId(SnowflakeIdUtil.nextId());
        audit.setAction(PUBLISH_ACTION);
        audit.setActorId(draft.getOwnerUserId());
        audit.setTargetId(skill.getSkillId());
        audit.setDetailsJson("{\"submissionId\":\"" + submission.getSubmissionId()
                + "\",\"approvalId\":\"" + approval.getApprovalId() + "\"}");
        audit.setCreateTime(now);
        audit.setUpdateTime(now);
        auditMapper.insert(audit);
        return new SkillPublicationRespDTO(skill.getSkillId().toString(), submission.getSubmissionId().toString(),
                approval.getApprovalId().toString(), version.getVersionId().toString(), approval.getStatus(),
                approval.getDecisionSource(), PUBLISHED, owned.publisher().getHandle(), skill.getSlug());
    }
}
