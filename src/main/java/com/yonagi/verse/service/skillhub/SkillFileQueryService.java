package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDownloadQueryMapper;
import com.yonagi.verse.dao.projection.SkillDownloadProjection;
import com.yonagi.verse.dto.resp.SkillFileContentRespDTO;
import com.yonagi.verse.dto.resp.SkillFileRespDTO;
import com.yonagi.verse.dto.resp.SkillFilesRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** 当前公开版本只读文件用例；可用性不缓存，S3 读取不持数据库事务。 */
@Service
@RequiredArgsConstructor
public class SkillFileQueryService {
    private static final int MAX_PATH_LENGTH = 512;
    private final SkillDownloadQueryMapper query;
    private final SkillDownloadAuthorizationService authorization;
    private final SkillArtifactMapper artifacts;
    private final SkillGithubSnapshotCodec codec;
    private final SkillArtifactPreviewReader previewReader;

    /** @return 固定版本内最多五百个文件的完整清单，不暴露私有对象位置 */
    public SkillFilesRespDTO files(Long skillId, Long versionId) {
        Snapshot snapshot = snapshot(skillId, versionId);
        List<SkillFileRespDTO> items = snapshot.manifest().files().stream()
                .sorted(Comparator.comparing(SkillValidatedFile::relativePath))
                .map(file -> new SkillFileRespDTO(file.relativePath(), file.sizeBytes(), file.sha256())).toList();
        confirm(snapshot);
        return new SkillFilesRespDTO(versionId.toString(), snapshot.manifest().packageName(),
                SkillFilePreviewPolicy.MAX_PREVIEW_BYTES, items);
    }

    /**
     * @param path 清单中的精确相对路径，不接受对象 key 或外部 URL
     * @return 只读正文或明确的不支持/超限元数据，空文本保持为空字符串
     */
    public SkillFileContentRespDTO content(Long skillId, Long versionId, String path) {
        SkillArtifactPreviewReader.validatePath(path);
        Snapshot snapshot = snapshot(skillId, versionId);
        SkillFileContentRespDTO result =
                previewReader.content(
                        snapshot.artifact(), snapshot.manifest(), versionId.toString(), path);
        confirm(snapshot);
        return result;
    }

    private Snapshot snapshot(Long skillId, Long versionId) {
        SkillDownloadProjection reference = query.selectAvailable(skillId, versionId);
        if (reference == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        SkillDownloadResource resource = authorize(versionId);
        SkillArtifactDO artifact = artifacts.selectById(reference.getArtifactId());
        if (artifact == null || !"READY".equals(artifact.getStatus())
                || !resource.archiveKey().equals(artifact.getArchiveKey())
                || !resource.sha256().equals(artifact.getArchiveSha256())
                || !Long.valueOf(resource.bytes()).equals(artifact.getArchiveBytes())) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        SkillPackageManifest manifest = codec.decode(artifact.getManifestJson(), SkillPackageManifest.class);
        if (manifest == null || manifest.packageName() == null || manifest.packageName().isBlank()
                || manifest.files() == null || manifest.files().size() > SkillPackageLimitConstant.MAX_FILE_COUNT
                || manifest.files().stream().anyMatch(file -> file == null || file.relativePath() == null
                || file.sha256() == null || file.sizeBytes() < 0)) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        return new Snapshot(resource, manifest, artifact);
    }

    private void confirm(Snapshot snapshot) {
        if (!snapshot.resource().equals(authorize(snapshot.resource().versionId()))) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
    }

    private SkillDownloadResource authorize(Long versionId) {
        try {
            return authorization.authorize(versionId);
        } catch (ClientException exception) {
            if (SkillErrorCodeEnum.VERSION_UNAVAILABLE.code().equals(exception.getErrorCode())) {
                throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
            }
            throw exception;
        }
    }

    private record Snapshot(
            SkillDownloadResource resource,
            SkillPackageManifest manifest,
            SkillArtifactDO artifact) {}
}
