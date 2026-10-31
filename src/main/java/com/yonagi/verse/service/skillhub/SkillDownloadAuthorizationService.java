package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillArtifactStatusEnum;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.enums.SkillStatusEnum;
import com.yonagi.verse.common.enums.SkillVersionStatusEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDownloadQueryMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 下载授权线性化点：账号→发布者→Skill→版本→制品当前读，不依赖查询缓存。 */
@Service
@RequiredArgsConstructor
public class SkillDownloadAuthorizationService {
    private final SkillAccessPolicy accessPolicy;
    private final SkillDownloadQueryMapper queryMapper;
    private final SkillVersionMapper versionMapper;
    private final SkillArtifactMapper artifactMapper;

    /**
     * @param versionId grant绑定的固定版本，不能改为当前其他版本
     * @return 最终当前读授权；锁随短事务释放，S3与传输不持事务
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillDownloadResource authorize(Long versionId) {
        Long ownerId = queryMapper.selectOwnerId(versionId);
        SkillVersionDO reference = versionMapper.selectById(versionId);
        if (ownerId == null || reference == null) {
            throw denied();
        }
        SkillDO skill = accessPolicy.lockSkill(ownerId, reference.getSkillId()).skill();
        if (!SkillStatusEnum.ACTIVE.name().equals(skill.getStatus())
                || !versionId.equals(skill.getCurrentVersionId())) {
            throw denied();
        }
        SkillVersionDO version = versionMapper.selectByIdForUpdate(versionId);
        if (version == null || !skill.getSkillId().equals(version.getSkillId())
                || !SkillVersionStatusEnum.AVAILABLE.name().equals(version.getStatus())) {
            throw denied();
        }
        SkillArtifactDO artifact = artifactMapper.selectByIdForUpdate(version.getArtifactId());
        if (artifact == null || !ownerId.equals(artifact.getOwnerUserId())
                || !SkillArtifactStatusEnum.READY.name().equals(artifact.getStatus())) {
            throw denied();
        }
        return new SkillDownloadResource(versionId, skill.getSlug() + "-" + version.getVersionLabel() + ".zip",
                artifact.getArchiveKey(), artifact.getArchiveSha256(), artifact.getArchiveBytes());
    }

    private ClientException denied() {
        return new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE);
    }
}
