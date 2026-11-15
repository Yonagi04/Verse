package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.resp.SkillFileChangeRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionPreviewRespDTO;
import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 预览与最终提交共用准入、基准、编号和确认摘要，最终提交使用当前读锁。 */
@Component
@RequiredArgsConstructor
public class SkillVersionPublicationPolicy {
    private final SkillVersionMapper versions;
    private final SkillArtifactMapper artifacts;
    private final SkillGithubSnapshotCodec json;
    private final SkillPublicationSnapshotCodec snapshots;
    private final SkillPublicationPolicy initialPolicy;
    private final SkillCreationSourcePolicy sources;

    /**
     * @return 从同一修订生成的固定预览，locked仅在持Skill锁的事务中使用
     */
    public Plan plan(SkillOwnedDraft owned, String type, boolean locked) {
        SkillDO skill = owned.skill();
        SkillDraftDO draft = owned.draft();
        boolean initial = skill.getCurrentVersionId() == null;
        if (type == null || !Set.of("DRAFT", "RELEASE").contains(type)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        if (!(initial ? "DRAFT" : "ACTIVE").equals(skill.getStatus())
                || !"READY".equals(draft.getStatus())
                || draft.getArtifactId() == null
                || draft.getBaseSkillRevision() == null
                || !draft.getBaseSkillRevision().equals(skill.getRevision())
                || draft.getRevision() == Long.MAX_VALUE) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        List<SkillVersionDO> refs =
                initial
                        ? List.of()
                        : versions.selectList(
                                Wrappers.lambdaQuery(SkillVersionDO.class)
                                        .eq(SkillVersionDO::getSkillId, skill.getSkillId())
                                        .and(
                                                ref ->
                                                        ref.eq(
                                                                        SkillVersionDO
                                                                                ::getVersionId,
                                                                        skill.getCurrentVersionId())
                                                                .or()
                                                                .eq(
                                                                        SkillVersionDO
                                                                                ::getSequenceNumber,
                                                                        skill.getVersionSequence())
                                                                .or(
                                                                        draft.getSourceVersionId()
                                                                                != null)
                                                                .eq(
                                                                        draft.getSourceVersionId()
                                                                                != null,
                                                                        SkillVersionDO
                                                                                ::getVersionId,
                                                                        draft.getSourceVersionId()))
                                        .orderByAsc(SkillVersionDO::getVersionId)
                                        .last(locked ? "FOR UPDATE" : ""));
        SkillVersionDO release = find(refs, skill.getCurrentVersionId());
        SkillVersionDO head =
                refs.stream()
                        .filter(
                                value ->
                                        Objects.equals(
                                                value.getSequenceNumber(),
                                                skill.getVersionSequence()))
                        .findFirst()
                        .orElse(null);
        if (!initial
                && (release == null
                        || head == null
                        || !"RELEASE".equals(release.getVersionType()))) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        SkillVersionRules.Numbering numbering = SkillVersionRules.next(skill, release, head, type);
        SkillVersionDO source = find(refs, draft.getSourceVersionId());
        if (draft.getSourceVersionId() != null
                && (!"RELEASE".equals(type)
                        || source == null
                        || head == null
                        || !source.getVersionId().equals(head.getVersionId())
                        || !"DRAFT".equals(source.getVersionType())
                        || !"AVAILABLE".equals(source.getStatus())
                        || !Integer.valueOf(numbering.release()).equals(source.getReleaseNumber())
                        || !draft.getArtifactId().equals(source.getArtifactId()))) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        SkillVersionDO base = "DRAFT".equals(type) ? head : release;
        if (base != null && !"AVAILABLE".equals(base.getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        List<Long> artifactIds =
                java.util.stream.Stream.of(
                                draft.getArtifactId(), base == null ? null : base.getArtifactId())
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();
        List<SkillArtifactDO> rows =
                artifacts.selectList(
                        Wrappers.lambdaQuery(SkillArtifactDO.class)
                                .in(SkillArtifactDO::getArtifactId, artifactIds)
                                .orderByAsc(SkillArtifactDO::getArtifactId)
                                .last(locked ? "FOR UPDATE" : ""));
        SkillArtifactDO artifact = artifact(rows, draft.getArtifactId(), draft.getOwnerUserId());
        SkillArtifactDO old =
                base == null ? null : artifact(rows, base.getArtifactId(), draft.getOwnerUserId());
        sources.validate(skill, artifact.getSourceType(), artifact.getSourceJson());
        List<SkillFileChangeRespDTO> changes =
                SkillManifestComparison.compare(manifest(old), manifest(artifact));
        SkillPublicationMetadataDTO metadata =
                initial
                        ? snapshots.decode(draft.getMetadataJson())
                        : snapshots.decode(release.getDeclarationsJson());
        String notes = initial ? metadata.releaseNotes() : draft.getReleaseNotes();
        if (notes == null || notes.isBlank() || notes.length() > 2000) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        metadata = snapshots.versionMetadata(metadata, numbering.label(), notes, !initial);
        if (initial) {
            initialPolicy.validateSubmission(metadata);
        }
        String digest =
                DigestUtil.sha256Hex(
                        json.encode(
                                new Confirmation(
                                        1,
                                        draft.getOwnerUserId().toString(),
                                        draft.getDraftId().toString(),
                                        skill.getSkillId().toString(),
                                        skill.getRevision().toString(),
                                        draft.getRevision().toString(),
                                        type,
                                        numbering.label(),
                                        artifact.getArtifactId().toString(),
                                        artifact.getContentSha256(),
                                        artifact.getSourceJson(),
                                        snapshots.encode(metadata),
                                        draft.getSourceVersionId(),
                                        base == null ? null : base.getVersionId()),
                                32768));
        return new Plan(
                numbering, type, artifact, metadata, base, release, source, changes, digest);
    }

    /**
     * @return 用户可核对的预览字段，不返回私有对象Key
     */
    public SkillVersionPreviewRespDTO preview(SkillOwnedDraft owned, Plan plan) {
        return new SkillVersionPreviewRespDTO(
                plan.type(),
                plan.numbering().label(),
                owned.draft().getRevision().toString(),
                owned.skill().getRevision().toString(),
                plan.digest(),
                id(plan.base()),
                label(plan.base()),
                plan.count("ADDED"),
                plan.count("MODIFIED"),
                plan.count("DELETED"),
                plan.artifact().getFileCount(),
                plan.artifact().getContentSha256(),
                json.source(plan.artifact().getSourceType(), plan.artifact().getSourceJson()),
                label(plan.source()),
                label(plan.release()),
                plan.metadata().releaseNotes());
    }

    private SkillPackageManifest manifest(SkillArtifactDO artifact) {
        if (artifact == null) {
            return null;
        }
        SkillPackageManifest value =
                json.decode(artifact.getManifestJson(), SkillPackageManifest.class);
        if (value == null) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        return value;
    }

    private SkillArtifactDO artifact(List<SkillArtifactDO> rows, Long id, Long owner) {
        SkillArtifactDO value =
                rows.stream()
                        .filter(row -> id.equals(row.getArtifactId()))
                        .findFirst()
                        .orElse(null);
        if (value == null
                || !owner.equals(value.getOwnerUserId())
                || !"READY".equals(value.getStatus())
                || value.getArchiveBytes() == null
                || value.getArchiveBytes() > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES
                || value.getFileCount() == null
                || value.getFileCount() > SkillPackageLimitConstant.MAX_FILE_COUNT) {
            throw new ClientException(SkillErrorCodeEnum.PACKAGE_INVALID);
        }
        return value;
    }

    private SkillVersionDO find(List<SkillVersionDO> refs, Long id) {
        return id == null
                ? null
                : refs.stream()
                        .filter(value -> id.equals(value.getVersionId()))
                        .findFirst()
                        .orElse(null);
    }

    private String id(SkillVersionDO value) {
        return value == null ? null : value.getVersionId().toString();
    }

    private String label(SkillVersionDO value) {
        return value == null ? null : SkillVersionRules.label(value);
    }

    private record Confirmation(
            int schema,
            String actorId,
            String draftId,
            String skillId,
            String skillRevision,
            String workspaceRevision,
            String type,
            String version,
            String artifactId,
            String contentSha256,
            String sourceJson,
            String metadataJson,
            Long sourceVersionId,
            Long baseVersionId) {}

    /** 已完成准入检查的发布计划，不能跨事务复用作最终授权。 */
    public record Plan(
            SkillVersionRules.Numbering numbering,
            String type,
            SkillArtifactDO artifact,
            SkillPublicationMetadataDTO metadata,
            SkillVersionDO base,
            SkillVersionDO release,
            SkillVersionDO source,
            List<SkillFileChangeRespDTO> changes,
            String digest) {
        public int count(String type) {
            return (int) changes.stream().filter(value -> type.equals(value.changeType())).count();
        }
    }
}
