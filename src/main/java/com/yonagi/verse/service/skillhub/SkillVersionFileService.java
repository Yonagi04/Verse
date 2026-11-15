package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.resp.SkillFileChangeRespDTO;
import com.yonagi.verse.dto.resp.SkillFileComparisonRespDTO;
import com.yonagi.verse.dto.resp.SkillFileContentRespDTO;
import com.yonagi.verse.dto.resp.SkillFileRespDTO;
import com.yonagi.verse.dto.resp.SkillFilesRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionChangesRespDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 私人文件与规定基准比较；读取归档前后分别检查两侧访问权，不开放整包导出。 */
@Service
@RequiredArgsConstructor
public class SkillVersionFileService {
    private final SkillVersionQueryService history;
    private final SkillVersionWorkspaceService workspace;
    private final SkillVersionMapper versions;
    private final SkillArtifactMapper artifacts;
    private final SkillGithubSnapshotCodec json;
    private final SkillArtifactPreviewReader reader;

    /**
     * @return 历史或待发布区的完整清单，versionId与revision恰好指定一个
     */
    public SkillFilesRespDTO files(
            Long userId, Long skillId, Long versionId, Long revision, String type) {
        Snapshot snapshot = snapshot(userId, skillId, versionId, revision, type, false);
        List<SkillFileRespDTO> items =
                snapshot.targetManifest().files().stream()
                        .sorted(Comparator.comparing(value -> value.relativePath()))
                        .map(
                                value ->
                                        new SkillFileRespDTO(
                                                value.relativePath(),
                                                value.sizeBytes(),
                                                value.sha256()))
                        .toList();
        confirm(userId, skillId, versionId, revision, type, false, snapshot);
        return new SkillFilesRespDTO(
                string(versionId),
                snapshot.targetManifest().packageName(),
                SkillFilePreviewPolicy.MAX_PREVIEW_BYTES,
                items);
    }

    /**
     * @return 精确清单路径的原文或能力状态，临时错误返回503
     */
    public SkillFileContentRespDTO content(
            Long userId, Long skillId, Long versionId, Long revision, String type, String path) {
        SkillArtifactPreviewReader.validatePath(path);
        Snapshot snapshot = snapshot(userId, skillId, versionId, revision, type, false);
        SkillFileContentRespDTO result =
                reader.content(
                        snapshot.target(), snapshot.targetManifest(), string(versionId), path);
        confirm(userId, skillId, versionId, revision, type, false, snapshot);
        return result;
    }

    /**
     * @return 基准由服务端固定的完整分类，读取失败不会返回空列表
     */
    public SkillVersionChangesRespDTO changes(
            Long userId, Long skillId, Long versionId, Long revision, String type) {
        Snapshot snapshot = snapshot(userId, skillId, versionId, revision, type, true);
        List<SkillFileChangeRespDTO> items =
                SkillManifestComparison.compare(snapshot.baseManifest(), snapshot.targetManifest());
        confirm(userId, skillId, versionId, revision, type, true, snapshot);
        return new SkillVersionChangesRespDTO(
                string(snapshot.baseId()), snapshot.baseLabel(), string(versionId), items);
    }

    /**
     * @return 两侧原文保持换行字节语义，缺失的一侧为null
     */
    public SkillFileComparisonRespDTO comparison(
            Long userId, Long skillId, Long versionId, Long revision, String type, String path) {
        SkillArtifactPreviewReader.validatePath(path);
        Snapshot snapshot = snapshot(userId, skillId, versionId, revision, type, true);
        SkillFileChangeRespDTO change =
                SkillManifestComparison.compare(snapshot.baseManifest(), snapshot.targetManifest())
                        .stream()
                        .filter(value -> path.equals(value.relativePath()))
                        .findFirst()
                        .orElseThrow(() -> new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE));
        SkillFileContentRespDTO oldFile =
                change.oldFile() == null
                        ? null
                        : reader.content(
                                snapshot.base(),
                                snapshot.baseManifest(),
                                string(snapshot.baseId()),
                                path);
        SkillFileContentRespDTO newFile =
                change.newFile() == null
                        ? null
                        : reader.content(
                                snapshot.target(),
                                snapshot.targetManifest(),
                                string(versionId),
                                path);
        confirm(userId, skillId, versionId, revision, type, true, snapshot);
        return new SkillFileComparisonRespDTO(
                string(snapshot.baseId()),
                string(versionId),
                change.changeType(),
                oldFile,
                newFile);
    }

    private Snapshot snapshot(
            Long userId,
            Long skillId,
            Long versionId,
            Long revision,
            String type,
            boolean compare) {
        Long artifactId;
        Long baseId;
        if (versionId != null) {
            SkillVersionDO node = history.node(userId, skillId, versionId);
            requireAvailable(node);
            artifactId = node.getArtifactId();
            baseId = node.getDiffBaseVersionId();
        } else {
            SkillOwnedDraft owned = workspace.read(userId, skillId);
            if (type == null
                    || !Set.of("DRAFT", "RELEASE").contains(type)
                    || revision == null
                    || !revision.equals(owned.draft().getRevision())
                    || !"READY".equals(owned.draft().getStatus())
                    || !Objects.equals(
                            owned.draft().getBaseSkillRevision(), owned.skill().getRevision())) {
                throw new ClientException(SkillErrorCodeEnum.CONFLICT);
            }
            if (owned.draft().getSourceVersionId() != null) {
                SkillVersionDO source =
                        history.node(userId, skillId, owned.draft().getSourceVersionId());
                requireAvailable(source);
                if (!"RELEASE".equals(type)
                        || !"DRAFT".equals(source.getVersionType())
                        || !source.getSequenceNumber().equals(owned.skill().getVersionSequence())
                        || !source.getArtifactId().equals(owned.draft().getArtifactId())) {
                    throw new ClientException(SkillErrorCodeEnum.CONFLICT);
                }
            }
            artifactId = owned.draft().getArtifactId();
            baseId = owned.skill().getCurrentVersionId();
            if ("DRAFT".equals(type)) {
                if (baseId == null) {
                    throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
                }
                SkillVersionDO head =
                        versions.selectOne(
                                Wrappers.lambdaQuery(SkillVersionDO.class)
                                        .eq(SkillVersionDO::getSkillId, skillId)
                                        .eq(
                                                SkillVersionDO::getSequenceNumber,
                                                owned.skill().getVersionSequence()));
                if (head == null) {
                    throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
                }
                baseId = head.getVersionId();
            }
        }
        SkillArtifactDO target = artifact(userId, artifactId);
        SkillArtifactDO base = null;
        String baseLabel = null;
        if (compare && baseId != null) {
            SkillVersionDO node = history.node(userId, skillId, baseId);
            requireAvailable(node);
            baseLabel = SkillVersionRules.label(node);
            base = artifact(userId, node.getArtifactId());
        }
        return new Snapshot(
                target, manifest(target), baseId, baseLabel, base, compare ? manifest(base) : null);
    }

    private void confirm(
            Long userId,
            Long skillId,
            Long versionId,
            Long revision,
            String type,
            boolean compare,
            Snapshot previous) {
        Snapshot current = snapshot(userId, skillId, versionId, revision, type, compare);
        if (!previous.target().getArtifactId().equals(current.target().getArtifactId())
                || !Objects.equals(previous.baseId(), current.baseId())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
    }

    private SkillArtifactDO artifact(Long userId, Long id) {
        SkillArtifactDO value = id == null ? null : artifacts.selectById(id);
        if (value == null
                || !userId.equals(value.getOwnerUserId())
                || !"READY".equals(value.getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return value;
    }

    private SkillPackageManifest manifest(SkillArtifactDO value) {
        if (value == null) {
            return null;
        }
        SkillPackageManifest result =
                json.decode(value.getManifestJson(), SkillPackageManifest.class);
        if (result == null) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        SkillManifestComparison.compare(null, result);
        return result;
    }

    private void requireAvailable(SkillVersionDO version) {
        if (!"AVAILABLE".equals(version.getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
    }

    private String string(Long value) {
        return value == null ? null : value.toString();
    }

    private record Snapshot(
            SkillArtifactDO target,
            SkillPackageManifest targetManifest,
            Long baseId,
            String baseLabel,
            SkillArtifactDO base,
            SkillPackageManifest baseManifest) {}
}
