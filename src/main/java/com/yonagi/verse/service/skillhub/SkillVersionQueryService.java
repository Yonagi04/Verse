package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.resp.SkillVersionPageRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionRespDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 私人历史实时查询，不与公开详情/下载权限互相兜底，不读取归档。 */
@Service
@RequiredArgsConstructor
public class SkillVersionQueryService {
    private static final int SIZE = 20;
    private final SkillAccessPolicy access;
    private final SkillVersionMapper versions;
    private final SkillArtifactMapper artifacts;
    private final SkillGithubSnapshotCodec json;
    private final SkillPublicationSnapshotCodec snapshots;

    /**
     * @return 仅本人可访问的资源，下架可读历史，平台禁用拒绝
     */
    public SkillOwnedResource owned(Long userId, Long skillId) {
        SkillOwnedResource resource = access.readSkill(userId, skillId);
        if (!Set.of("ACTIVE", "OWNER_UNPUBLISHED").contains(resource.skill().getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return resource;
    }

    /**
     * @return 持有者授权后的精确节点，不将BLOCKED伪装成不存在的历史
     */
    public SkillVersionDO node(Long userId, Long skillId, Long versionId) {
        owned(userId, skillId);
        SkillVersionDO version = versions.selectById(versionId);
        if (version == null || !skillId.equals(version.getSkillId())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return version;
    }

    /**
     * @return head固定的有界分页，新节点不会造成后续页漂移
     */
    public SkillVersionPageRespDTO timeline(
            Long userId, Long skillId, int page, int size, Long head) {
        SkillOwnedResource resource = owned(userId, skillId);
        long maximum = resource.skill().getVersionSequence();
        if (page < 1
                || page > 10000
                || size != SIZE
                || (head != null && (head < 0 || head > maximum))) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        long fixed = head == null ? maximum : head;
        long total =
                versions.selectCount(
                        Wrappers.lambdaQuery(SkillVersionDO.class)
                                .eq(SkillVersionDO::getSkillId, skillId)
                                .le(SkillVersionDO::getSequenceNumber, fixed));
        List<SkillVersionDO> rows =
                total == 0
                        ? List.of()
                        : versions.selectList(
                                Wrappers.lambdaQuery(SkillVersionDO.class)
                                        .select(
                                                SkillVersionDO.class,
                                                field ->
                                                        !"declarations_json"
                                                                .equals(field.getColumn()))
                                        .eq(SkillVersionDO::getSkillId, skillId)
                                        .le(SkillVersionDO::getSequenceNumber, fixed)
                                        .orderByDesc(SkillVersionDO::getSequenceNumber)
                                        .last("LIMIT 20 OFFSET " + ((page - 1) * SIZE)));
        List<SkillVersionRespDTO> items = views(resource, rows, false);
        owned(userId, skillId);
        return new SkillVersionPageRespDTO(page, size, total, Long.toString(fixed), items);
    }

    /**
     * @return 节点详情与当时沿用的信息，不返回旧SemVer
     */
    public SkillVersionRespDTO detail(Long userId, Long skillId, Long versionId) {
        SkillOwnedResource resource = owned(userId, skillId);
        SkillVersionRespDTO result =
                views(resource, List.of(node(userId, skillId, versionId)), true).getFirst();
        owned(userId, skillId);
        return result;
    }

    private List<SkillVersionRespDTO> views(
            SkillOwnedResource resource, List<SkillVersionDO> rows, boolean detail) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> artifactIds =
                rows.stream().map(SkillVersionDO::getArtifactId).distinct().toList();
        Map<Long, SkillArtifactDO> files = new HashMap<>();
        artifacts
                .selectList(
                        Wrappers.lambdaQuery(SkillArtifactDO.class)
                                .select(
                                        SkillArtifactDO::getArtifactId,
                                        SkillArtifactDO::getOwnerUserId,
                                        SkillArtifactDO::getStatus,
                                        SkillArtifactDO::getSourceType,
                                        SkillArtifactDO::getSourceJson)
                                .in(SkillArtifactDO::getArtifactId, artifactIds))
                .forEach(value -> files.put(value.getArtifactId(), value));
        List<Long> baseIds =
                rows.stream()
                        .flatMap(value -> Stream.of(value.getDiffBaseVersionId(), value.getSourceDraftVersionId()))
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, String> labels = new HashMap<>();
        if (!baseIds.isEmpty()) {
            versions.selectList(
                            Wrappers.lambdaQuery(SkillVersionDO.class)
                                    .select(
                                            SkillVersionDO::getVersionId,
                                            SkillVersionDO::getReleaseNumber,
                                            SkillVersionDO::getDraftNumber)
                                    .eq(SkillVersionDO::getSkillId, resource.skill().getSkillId())
                                    .in(SkillVersionDO::getVersionId, baseIds))
                    .forEach(
                            value ->
                                    labels.put(
                                            value.getVersionId(), SkillVersionRules.label(value)));
        }
        Map<Long, String> uses = new HashMap<>();
        versions.selectList(
                        Wrappers.lambdaQuery(SkillVersionDO.class)
                                .select(
                                        SkillVersionDO::getSourceDraftVersionId,
                                        SkillVersionDO::getReleaseNumber,
                                        SkillVersionDO::getDraftNumber)
                                .eq(SkillVersionDO::getSkillId, resource.skill().getSkillId())
                                .eq(SkillVersionDO::getVersionType, "RELEASE")
                                .in(
                                        SkillVersionDO::getSourceDraftVersionId,
                                        rows.stream().map(SkillVersionDO::getVersionId).toList()))
                .forEach(
                        value ->
                                uses.put(
                                        value.getSourceDraftVersionId(),
                                        SkillVersionRules.label(value)));
        return rows.stream()
                .map(
                        value -> {
                            SkillArtifactDO artifact = files.get(value.getArtifactId());
                    if (artifact != null && !resource.publisher().getUserId().equals(artifact.getOwnerUserId())) {
                        artifact = null;
                    }
                            boolean available =
                                    "AVAILABLE".equals(value.getStatus())
                                            && artifact != null
                                            && resource.publisher()
                                                    .getUserId()
                                                    .equals(artifact.getOwnerUserId())
                                            && "READY".equals(artifact.getStatus());
                            return new SkillVersionRespDTO(
                                    string(value.getVersionId()),
                                    value.getVersionType(),
                                    SkillVersionRules.label(value),
                                    string(value.getSequenceNumber()),
                                    value.getStatus(),
                                    value.getPublishedAt().atOffset(ZoneOffset.UTC),
                                    value.getReleaseNotes(),
                                    source(artifact),
                                    detail
                                            ? snapshots.versionMetadata(
                                                    snapshots.decode(value.getDeclarationsJson()),
                                                    SkillVersionRules.label(value),
                                                    value.getReleaseNotes(),
                                                    value.getSourceDraftVersionId() != null)
                                            : null,
                                    value.getVersionId()
                                            .equals(resource.skill().getCurrentVersionId()),
                                    available
                                            && "ACTIVE".equals(resource.skill().getStatus())
                                            && "DRAFT".equals(value.getVersionType())
                                            && value.getSequenceNumber()
                                                    .equals(resource.skill().getVersionSequence()),
                                    string(value.getSourceDraftVersionId()), labels.get(value.getSourceDraftVersionId()),
                                    uses.get(value.getVersionId()),
                                    string(value.getDiffBaseVersionId()),
                                    labels.get(value.getDiffBaseVersionId()),
                                    value.getAddedCount() == null || value.getModifiedCount() == null || value.getDeletedCount() == null
                            ? "UNAVAILABLE" : "READY",
                                    value.getAddedCount(),
                                    value.getModifiedCount(),
                                    value.getDeletedCount(),
                                    available);
                        })
                .toList();
    }

    private SkillSourceDTO source(SkillArtifactDO value) {
        if (value == null) {
            return null;
        }
        try {
            return json.source(value.getSourceType(), value.getSourceJson());
        } catch (ServerException unavailable) {
            // 来源元数据损坏不能抹掉节点事实；详情用明确的来源不可用展示。
            return null;
        }
    }

    private String string(Long value) {
        return value == null ? null : value.toString();
    }
}
