package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dto.resp.SkillDraftFileRespDTO;
import com.yonagi.verse.dto.resp.SkillDraftFilesRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 本人READY文件清单不缓存，来源是当前绑定制品，不能按任意对象Key读取。 */
@Service
@RequiredArgsConstructor
public class SkillDraftFileService {
    private final SkillAccessPolicy access;
    private final SkillArtifactMapper artifacts;
    private final SkillGithubSnapshotCodec codec;

    /** @return 固定制品标识和内容摘要随分页返回，前端据此拒绝旧制品迟到响应 */
    public SkillDraftFilesRespDTO files(Long userId, Long draftId, int page, int size) {
        if (page < 1 || page > 10 || !Set.of(50, 100).contains(size)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        SkillOwnedDraft owned = access.readDraft(userId, draftId);
        if (!"READY".equals(owned.draft().getStatus()) || owned.draft().getArtifactId() == null) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        SkillArtifactDO artifact = artifacts.selectById(owned.draft().getArtifactId());
        if (artifact == null || !userId.equals(artifact.getOwnerUserId()) || !"READY".equals(artifact.getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        SkillPackageManifest manifest = codec.decode(artifact.getManifestJson(), SkillPackageManifest.class);
        if (manifest == null || manifest.files() == null || manifest.files().size() > 500) {
            throw new ClientException(SkillErrorCodeEnum.PACKAGE_INVALID);
        }
        List<SkillValidatedFile> values = manifest.files().stream()
                .sorted(Comparator.comparing(SkillValidatedFile::relativePath)).toList();
        int start = Math.min(values.size(), (page - 1) * size);
        List<SkillDraftFileRespDTO> items = values.subList(start, Math.min(values.size(), start + size)).stream()
                .map(value -> new SkillDraftFileRespDTO(value.relativePath(), value.sizeBytes(), value.sha256())).toList();
        return new SkillDraftFilesRespDTO(page, size, values.size(), items, artifact.getArtifactId().toString(),
                artifact.getContentSha256());
    }
}
