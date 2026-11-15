package com.yonagi.verse.service.skillhub;

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
import com.yonagi.verse.dto.resp.SkillVersionReceiptRespDTO;
import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 单事务写入冻结修订、审批、版本及公开指针，不做文件或网络操作。 */
@Component
@RequiredArgsConstructor
public class SkillVersionWriter {
    private static final String SUBMITTED = "SUBMITTED";
    private static final String PUBLISH_ACTION = "PUBLISH";
    private final SkillDisplayRevisionMapper revisionMapper;
    private final SkillSubmissionMapper submissionMapper;
    private final SkillApprovalService approvalService;
    private final SkillVersionMapper versionMapper;
    private final SkillMapper skillMapper;
    private final SkillDraftMapper draftMapper;
    private final SkillAuditRecordMapper auditMapper;
    private final SkillPublicationSnapshotCodec snapshotCodec;

    /**
     * @param owned 已验证归属且持统一锁序的草稿
     * @param plan 持锁生成且通过确认摘要校验的计划
     * @param key 长期发布幂等键，旧首发适配为空
     * @param requestHash 固定命令摘要
     * @return 原事务内发布结果，任一步失败由外层回滚全部事实
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SkillVersionReceiptRespDTO publish(
            SkillOwnedDraft owned,
            SkillVersionPublicationPolicy.Plan plan,
            String key,
            String requestHash) {
        SkillArtifactDO artifact = plan.artifact();
        SkillPublicationMetadataDTO metadata = plan.metadata();
        boolean release = "RELEASE".equals(plan.type());
        boolean initial = owned.skill().getCurrentVersionId() == null;
        LocalDateTime now =
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        SkillDraftDO draft = owned.draft();
        SkillDO skill = owned.skill();
        Long displayId = skill.getPublicRevisionId();
        if (initial) {
            SkillDisplayRevisionDO display = new SkillDisplayRevisionDO();
            display.setRevisionId(SnowflakeIdUtil.nextId());
            display.setSkillId(skill.getSkillId());
            display.setName(metadata.name());
            display.setSummary(metadata.summary());
            display.setCategoryId(metadata.categoryId());
            display.setCreateTime(now);
            display.setUpdateTime(now);
            if (revisionMapper.insert(display) != 1) {
                throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
            }
            for (String tag : metadata.tags()) {
                revisionMapper.insertTag(display.getRevisionId(), tag);
            }

            displayId = display.getRevisionId();
        }
        SkillSubmissionDO submission = null;
        SkillApprovalDO approval = null;
        if (release) {
            String snapshotHash =
                    cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                            snapshotCodec.versionHash(
                                    metadata,
                                    artifact.getContentSha256(),
                                    artifact.getSourceType(),
                                    artifact.getSourceJson(),
                                    plan.release() == null ? null : plan.release().getVersionId()));
            submission = new SkillSubmissionDO();
            submission.setSubmissionId(SnowflakeIdUtil.nextId());
            submission.setSkillId(skill.getSkillId());
            submission.setDraftId(draft.getDraftId());
            submission.setDraftRevision(draft.getRevision());
            submission.setSubmittedBy(draft.getOwnerUserId());
            submission.setDisplayRevisionId(displayId);
            submission.setArtifactId(artifact.getArtifactId());
            submission.setVersionLabel(metadata.version());
            submission.setVersionId(SnowflakeIdUtil.nextId());
            submission.setSnapshotHash(snapshotHash);
            submission.setStatus(SUBMITTED);
            submission.setCreateTime(now);
            submission.setUpdateTime(now);
            if (submissionMapper.insert(submission) != 1) {
                throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
            }
            approval = approvalService.createAndApprove(submission.getSubmissionId(), snapshotHash);
            if (!SkillApprovalStatusEnum.APPROVED.name().equals(approval.getStatus())
                    || !snapshotHash.equals(approval.getRequestSnapshotHash())
                    || !submission.getSubmissionId().equals(approval.getSubmissionId())) {
                throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
            }
        }
        SkillVersionDO version = new SkillVersionDO();
        version.setVersionId(release ? submission.getVersionId() : SnowflakeIdUtil.nextId());
        version.setSkillId(skill.getSkillId());
        version.setVersionLabel(metadata.version());
        version.setArtifactId(artifact.getArtifactId());
        version.setApprovedSubmissionId(release ? submission.getSubmissionId() : null);
        version.setStatus(SkillVersionStatusEnum.AVAILABLE.name());
        version.setReleaseNotes(metadata.releaseNotes());
        version.setDeclarationsJson(snapshotCodec.encode(metadata));
        version.setVersionType(plan.type());
        version.setReleaseNumber(plan.numbering().release());
        version.setDraftNumber(plan.numbering().draft());
        version.setSequenceNumber(plan.numbering().sequence());
        version.setDisplayRevisionId(displayId);
        version.setSourceDraftVersionId(
                plan.source() == null ? null : plan.source().getVersionId());
        version.setDiffBaseVersionId(plan.base() == null ? null : plan.base().getVersionId());
        version.setAddedCount(plan.count("ADDED"));
        version.setModifiedCount(plan.count("MODIFIED"));
        version.setDeletedCount(plan.count("DELETED"));
        version.setPublishedBy(draft.getOwnerUserId());
        version.setPublishKey(key);
        version.setPublishRequestHash(requestHash);
        version.setPublishedAt(now);
        version.setCreateTime(now);
        version.setUpdateTime(now);
        if (versionMapper.insert(version) != 1) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        int changed =
                skillMapper.update(
                        null,
                        Wrappers.lambdaUpdate(SkillDO.class)
                                .eq(SkillDO::getSkillId, skill.getSkillId())
                                .eq(SkillDO::getRevision, skill.getRevision())
                                .eq(SkillDO::getStatus, skill.getStatus())
                                .set(release, SkillDO::getStatus, SkillStatusEnum.ACTIVE.name())
                                .set(release, SkillDO::getCurrentVersionId, version.getVersionId())
                                .set(initial, SkillDO::getCreationVersionId, version.getVersionId())
                                .set(initial, SkillDO::getPublicRevisionId, displayId)
                                .set(release, SkillDO::getLatestReleaseAt, now)
                                .set(SkillDO::getVersionSequence, plan.numbering().sequence())
                                .set(SkillDO::getRevision, skill.getRevision() + 1)
                                .set(SkillDO::getUpdateTime, now));
        int consumed =
                draftMapper.update(
                        null,
                        Wrappers.lambdaUpdate(SkillDraftDO.class)
                                .eq(SkillDraftDO::getDraftId, draft.getDraftId())
                                .eq(SkillDraftDO::getRevision, draft.getRevision())
                                .eq(SkillDraftDO::getStatus, SkillDraftStatusEnum.READY.name())
                                .set(SkillDraftDO::getStatus, SkillDraftStatusEnum.SUBMITTED.name())
                                .set(SkillDraftDO::getRevision, draft.getRevision() + 1)
                                .set(SkillDraftDO::getUpdateTime, now));
        if (changed != 1 || consumed != 1) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        if (release) {
            submission.setStatus(SkillApprovalStatusEnum.APPROVED.name());
            if (submissionMapper.updateById(submission) != 1) {
                throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
            }
        }
        SkillAuditRecordDO audit = new SkillAuditRecordDO();
        audit.setAuditId(SnowflakeIdUtil.nextId());
        audit.setAction(PUBLISH_ACTION);
        audit.setActorId(draft.getOwnerUserId());
        audit.setTargetId(skill.getSkillId());
        audit.setDetailsJson(
                "{\"versionId\":\""
                        + version.getVersionId()
                        + "\",\"type\":\""
                        + plan.type()
                        + "\"}");
        audit.setCreateTime(now);
        audit.setUpdateTime(now);
        if (auditMapper.insert(audit) != 1) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        return new SkillVersionReceiptRespDTO(
                skill.getSkillId().toString(),
                version.getVersionId().toString(),
                plan.type(),
                plan.numbering().label(),
                Long.toString(plan.numbering().sequence()),
                now.atOffset(ZoneOffset.UTC),
                plan.source() == null ? null : plan.source().getVersionId().toString(),
                release ? submission.getSubmissionId().toString() : null,
                release ? approval.getApprovalId().toString() : null);
    }
}
