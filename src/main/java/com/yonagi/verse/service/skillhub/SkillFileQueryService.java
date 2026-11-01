package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
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
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 当前公开版本只读文件用例；可用性不缓存，S3 读取不持数据库事务。 */
@Service
@RequiredArgsConstructor
public class SkillFileQueryService {
    private static final int MAX_PATH_LENGTH = 512;
    private final SkillDownloadQueryMapper query;
    private final SkillDownloadAuthorizationService authorization;
    private final SkillArtifactMapper artifacts;
    private final SkillGithubSnapshotCodec codec;
    private final SkillArtifactStore store;
    private final Semaphore previews = new Semaphore(2);
    private final SkillFilePreviewPolicy policy = new SkillFilePreviewPolicy();
    private final SkillArchivePreviewReader reader = new SkillArchivePreviewReader();

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
        validatePath(path);
        if (!previews.tryAcquire()) {
            throw new ClientException(SkillErrorCodeEnum.RATE_LIMITED);
        }
        try {
            Snapshot snapshot = snapshot(skillId, versionId);
            SkillValidatedFile file = snapshot.manifest().files().stream()
                    .filter(value -> path.equals(value.relativePath())).findFirst()
                    .orElseThrow(() -> new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE));
            SkillFilePreviewPolicy.Format format = policy.format(path);
            String status = "UNSUPPORTED";
            String content = null;
            if (!"UNSUPPORTED".equals(format.kind())) {
                status = "TOO_LARGE";
                if (file.sizeBytes() <= SkillFilePreviewPolicy.MAX_PREVIEW_BYTES) {
                    byte[] archive = store.get(snapshot.resource().archiveKey(),
                            (int) SkillPackageLimitConstant.MAX_ARCHIVE_BYTES);
                    if (archive.length != snapshot.resource().bytes()
                            || !DigestUtil.sha256Hex(archive).equals(snapshot.resource().sha256())) {
                        throw new ServerException("Skill归档字节校验失败", SkillErrorCodeEnum.UNAVAILABLE);
                    }
                    content = policy.text(reader.read(archive, snapshot.manifest().packageName(), file));
                    status = content == null ? "UNSUPPORTED" : "READY";
                }
            }
            confirm(snapshot);
            return new SkillFileContentRespDTO(versionId.toString(), path, file.sizeBytes(), file.sha256(),
                    format.kind(), format.language(), status, content);
        } finally {
            previews.release();
        }
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
        return new Snapshot(resource, manifest);
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

    private void validatePath(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.chars().anyMatch(Character::isISOControl)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
            }
        }
    }

    private record Snapshot(SkillDownloadResource resource, SkillPackageManifest manifest) {
    }
}
