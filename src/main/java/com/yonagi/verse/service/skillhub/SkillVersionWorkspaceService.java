package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.req.SkillWorkspaceOpenReqDTO;
import com.yonagi.verse.dto.req.SkillWorkspaceSaveReqDTO;
import com.yonagi.verse.dto.resp.SkillVersionPreviewRespDTO;
import com.yonagi.verse.dto.resp.SkillWorkspaceRespDTO;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/** 唯一待发布区的显式重开、恢复与说明编辑，不覆盖任何已发布节点。 */
@Service
@RequiredArgsConstructor
public class SkillVersionWorkspaceService {
    private final SkillAccessPolicy access;
    private final SkillDraftMapper drafts;
    private final SkillVersionMapper versions;
    private final SkillArtifactMapper artifacts;
    private final SkillCreationSourcePolicy sources;
    private final SkillPublicationSnapshotCodec snapshots;
    private final SkillGithubSnapshotCodec json;
    private final SkillIdempotencyService commands;
    private final SkillVersionPublicationPolicy publication;
    private final Validator validator;

    /**
     * @return 实时归属及资源状态校验后的唯一待发布区
     */
    public SkillOwnedDraft read(Long userId, Long skillId) {
        SkillOwnedResource owned = access.readSkill(userId, skillId);
        if (!Set.of("DRAFT", "ACTIVE", "OWNER_UNPUBLISHED").contains(owned.skill().getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        SkillDraftDO draft =
                drafts.selectOne(
                        Wrappers.lambdaQuery(SkillDraftDO.class)
                                .eq(SkillDraftDO::getSkillId, skillId)
                                .eq(SkillDraftDO::getOwnerUserId, userId));
        if (draft == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return new SkillOwnedDraft(owned.publisher(), owned.skill(), draft);
    }

    /**
     * @return 无隐式写入的待发布区快照
     */
    public SkillWorkspaceRespDTO get(Long userId, Long skillId) {
        return view(read(userId, skillId));
    }

    /**
     * @return 显式初始化的下一次准备；活跃内容需先经放弃接口取消
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillWorkspaceRespDTO open(
            Long userId, Long skillId, SkillWorkspaceOpenReqDTO input, String key) {
        validate(input);
        SkillOwnedResource resource = access.lockSkill(userId, skillId);
        String command = "OPEN_VERSION_WORKSPACE";
        java.util.List<Object> payload = java.util.List.of(skillId, input);
        Long replay = commands.replay(userId, command, key, payload, Long.class);
        if (replay != null) {
            return get(userId, skillId);
        }
        if (!"ACTIVE".equals(resource.skill().getStatus())
                || !input.expectedSkillRevision().equals(resource.skill().getRevision())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        SkillDraftDO reference =
                drafts.selectOne(
                        Wrappers.lambdaQuery(SkillDraftDO.class)
                                .eq(SkillDraftDO::getSkillId, skillId));
        SkillDraftDO draft =
                reference == null ? null : drafts.selectByIdForUpdate(reference.getDraftId());
        if (draft == null
                || Set.of("EDITING", "READY").contains(draft.getStatus())
                || draft.getRevision() == Long.MAX_VALUE) {
            throw new ClientException("已有待发布内容，请继续或明确放弃后重新准备", SkillErrorCodeEnum.CONFLICT);
        }
        sources.source(resource.skill());
        Long artifactId = null;
        Long sourceId = null;
        String notes = null;
        if ("LATEST_DRAFT".equals(input.mode())) {
            SkillVersionDO head =
                    versions.selectOne(
                            Wrappers.lambdaQuery(SkillVersionDO.class)
                                    .eq(SkillVersionDO::getSkillId, skillId)
                                    .eq(
                                            SkillVersionDO::getSequenceNumber,
                                            resource.skill().getVersionSequence())
                                    .last("FOR UPDATE"));
            if (head == null
                    || !"DRAFT".equals(head.getVersionType())
                    || !"AVAILABLE".equals(head.getStatus())) {
                throw new ClientException(SkillErrorCodeEnum.CONFLICT);
            }
            SkillArtifactDO artifact = artifacts.selectByIdForUpdate(head.getArtifactId());
            if (artifact == null
                    || !userId.equals(artifact.getOwnerUserId())
                    || !"READY".equals(artifact.getStatus())) {
                throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
            }
            artifactId = artifact.getArtifactId();
            sourceId = head.getVersionId();
            notes = head.getReleaseNotes();
        }
        long nextRevision = draft.getRevision() + 1;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int changed =
                drafts.update(
                        null,
                        Wrappers.lambdaUpdate(SkillDraftDO.class)
                                .eq(SkillDraftDO::getDraftId, draft.getDraftId())
                                .eq(SkillDraftDO::getRevision, draft.getRevision())
                                .set(SkillDraftDO::getArtifactId, artifactId)
                                .set(SkillDraftDO::getLatestJobId, null)
                                .set(SkillDraftDO::getMetadataJson, null)
                                .set(SkillDraftDO::getVersionLabel, null)
                                .set(SkillDraftDO::getSourceVersionId, sourceId)
                                .set(SkillDraftDO::getReleaseNotes, notes)
                                .set(
                                        SkillDraftDO::getBaseSkillRevision,
                                        resource.skill().getRevision())
                                .set(
                                        SkillDraftDO::getStatus,
                                        sourceId == null ? "EDITING" : "READY")
                                .set(SkillDraftDO::getRevision, nextRevision)
                                .set(SkillDraftDO::getUpdateTime, now));
        if (changed != 1) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        commands.record(userId, command, key, payload, draft.getDraftId(), draft.getDraftId());
        return get(userId, skillId);
    }

    /**
     * @return 保存本次更新说明后的修订；不接受全量基础信息
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillWorkspaceRespDTO save(Long userId, Long skillId, SkillWorkspaceSaveReqDTO input) {
        validate(input);
        SkillOwnedDraft reference = read(userId, skillId);
        SkillOwnedDraft owned = access.lockDraft(userId, reference.draft().getDraftId());
        if (!"ACTIVE".equals(owned.skill().getStatus())
                || !input.revision().equals(owned.draft().getRevision())
                || !Set.of("EDITING", "READY").contains(owned.draft().getStatus())
                || owned.draft().getRevision() == Long.MAX_VALUE) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        int changed =
                drafts.update(
                        null,
                        Wrappers.lambdaUpdate(SkillDraftDO.class)
                                .eq(SkillDraftDO::getDraftId, owned.draft().getDraftId())
                                .eq(SkillDraftDO::getRevision, input.revision())
                                .set(SkillDraftDO::getReleaseNotes, input.releaseNotes())
                                .set(SkillDraftDO::getRevision, input.revision() + 1)
                                .set(
                                        SkillDraftDO::getUpdateTime,
                                        LocalDateTime.now(ZoneOffset.UTC)));
        if (changed != 1) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        return get(userId, skillId);
    }

    /**
     * @return 服务端权威的版本、差异与确认摘要，读取前后复核状态
     */
    public SkillVersionPreviewRespDTO preview(Long userId, Long skillId, String type) {
        SkillOwnedDraft owned = read(userId, skillId);
        SkillVersionPublicationPolicy.Plan plan = publication.plan(owned, type, false);
        SkillOwnedDraft latest = read(userId, skillId);
        SkillVersionPublicationPolicy.Plan checked = publication.plan(latest, type, false);
        if (!plan.digest().equals(checked.digest())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        return publication.preview(owned, plan);
    }

    private SkillWorkspaceRespDTO view(SkillOwnedDraft owned) {
        SkillDraftDO draft = owned.draft();
        SkillVersionDO release =
                owned.skill().getCurrentVersionId() == null
                        ? null
                        : versions.selectById(owned.skill().getCurrentVersionId());
        SkillArtifactDO artifact =
                draft.getArtifactId() == null ? null : artifacts.selectById(draft.getArtifactId());
        if (artifact != null
                && (!draft.getOwnerUserId().equals(artifact.getOwnerUserId())
                        || !"READY".equals(artifact.getStatus()))) {
            artifact = null;
        }
        if (draft.getSourceVersionId() != null) {
            SkillVersionDO source = versions.selectById(draft.getSourceVersionId());
            if (source == null || !"AVAILABLE".equals(source.getStatus())) {
                artifact = null;
            }
        }
        SkillVersionDO head = release == null ? null : versions.selectOne(Wrappers.lambdaQuery(SkillVersionDO.class)
                .eq(SkillVersionDO::getSkillId, owned.skill().getSkillId())
                .eq(SkillVersionDO::getSequenceNumber, owned.skill().getVersionSequence()));
        boolean latestDraft = head != null && "DRAFT".equals(head.getVersionType());
        boolean canPromote = latestDraft && "AVAILABLE".equals(head.getStatus()) && "AVAILABLE".equals(release.getStatus())
                && "ACTIVE".equals(owned.skill().getStatus())
                && release.getReleaseNumber() != Integer.MAX_VALUE
                && Integer.valueOf(release.getReleaseNumber() + 1).equals(head.getReleaseNumber());
        return new SkillWorkspaceRespDTO(
                owned.skill().getSkillId().toString(),
                draft.getDraftId().toString(),
                owned.skill().getSlug(),
                owned.skill().getStatus(),
                owned.skill().getRevision().toString(),
                string(draft.getBaseSkillRevision()),
                draft.getRevision().toString(),
                draft.getStatus(),
                string(draft.getLatestJobId()),
                artifact == null ? null : string(artifact.getArtifactId()),
                artifact == null ? null : artifact.getContentSha256(),
                string(draft.getSourceVersionId()),
                draft.getReleaseNotes(),
                release == null
                        ? (draft.getMetadataJson() == null
                                ? null
                                : snapshots.decode(draft.getMetadataJson()))
                        : snapshots.decode(release.getDeclarationsJson()),
                release == null ? null : sources.source(owned.skill()),
                artifact == null
                        ? null
                        : json.source(artifact.getSourceType(), artifact.getSourceJson()),
                release == null ? null : SkillVersionRules.label(release),
                owned.skill().getLatestReleaseAt() == null ? null : owned.skill().getLatestReleaseAt().atOffset(ZoneOffset.UTC),
                release == null || release.getReleaseNumber() == Integer.MAX_VALUE ? null : "v" + (release.getReleaseNumber() + 1),
                latestDraft ? SkillVersionRules.label(head) : null,
                latestDraft && head.getPublishedAt() != null ? head.getPublishedAt().atOffset(ZoneOffset.UTC) : null,
                canPromote);
    }

    private String string(Long value) {
        return value == null ? null : value.toString();
    }

    private void validate(Object value) {
        if (value == null || !validator.validate(value).isEmpty()) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
    }
}
