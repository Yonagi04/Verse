package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;

import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dto.resp.SkillFileContentRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/** 公共/私人预览共用两份内存预算；本组件不决定资源权限。 */
@Component
@RequiredArgsConstructor
public class SkillArtifactPreviewReader {
    private static final int MAX_PATH_LENGTH = 512;
    private final SkillArtifactStore store;
    private final Semaphore previews = new Semaphore(2);
    private final SkillFilePreviewPolicy policy = new SkillFilePreviewPolicy();
    private final SkillArchivePreviewReader archiveReader = new SkillArchivePreviewReader();

    /**
     * @return 有界且核对归档及文件摘要的原文；不支持与读取失败明确区分
     */
    public SkillFileContentRespDTO content(
            SkillArtifactDO artifact,
            SkillPackageManifest manifest,
            String versionId,
            String path) {
        validatePath(path);
        SkillValidatedFile file =
                manifest.files().stream()
                        .filter(value -> path.equals(value.relativePath()))
                        .findFirst()
                        .orElseThrow(() -> new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE));
        SkillFilePreviewPolicy.Format format = policy.format(path);
        String status = "UNSUPPORTED";
        String content = null;
        if (!"UNSUPPORTED".equals(format.kind())) {
            status = "TOO_LARGE";
            if (file.sizeBytes() <= SkillFilePreviewPolicy.MAX_PREVIEW_BYTES) {
                if (!previews.tryAcquire()) {
                    throw new ClientException(SkillErrorCodeEnum.RATE_LIMITED);
                }
                try {
                    byte[] archive =
                            store.get(
                                    artifact.getArchiveKey(),
                                    (int) SkillPackageLimitConstant.MAX_ARCHIVE_BYTES);
                    if (artifact.getArchiveBytes() == null
                            || archive.length != artifact.getArchiveBytes()
                            || !DigestUtil.sha256Hex(archive).equals(artifact.getArchiveSha256())) {
                        throw new ServerException("Skill归档字节校验失败", SkillErrorCodeEnum.UNAVAILABLE);
                    }
                    content =
                            policy.text(archiveReader.read(archive, manifest.packageName(), file));
                    status = content == null ? "UNSUPPORTED" : "READY";
                } finally {
                    previews.release();
                }
            }
        }
        return new SkillFileContentRespDTO(
                versionId,
                path,
                file.sizeBytes(),
                file.sha256(),
                format.kind(),
                format.language(),
                status,
                content);
    }

    public static void validatePath(String path) {
        if (path == null
                || path.isBlank()
                || path.length() > MAX_PATH_LENGTH
                || path.startsWith("/")
                || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0
                || path.chars().anyMatch(Character::isISOControl)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
            }
        }
    }
}
