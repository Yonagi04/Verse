package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.req.SkillGithubImportCreateReqDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** 唯一创建来源权威来自首次正式版，不能依赖可清理的导入任务。 */
@Component
@RequiredArgsConstructor
public class SkillCreationSourcePolicy {
    private final SkillVersionMapper versions;
    private final SkillArtifactMapper artifacts;
    private final SkillGithubSnapshotCodec codec;
    private final SkillGithubSourcePolicy github;

    /**
     * @return 是否为已有正式版本的后续更新
     */
    public boolean updating(SkillDO skill) {
        return skill.getCurrentVersionId() != null;
    }

    /** 校验准备/提交状态；不得利用导入绕过下架。 */
    public void requirePreparing(SkillOwnedDraft owned) {
        String expected = updating(owned.skill()) ? "ACTIVE" : "DRAFT";
        if (!expected.equals(owned.skill().getStatus())
                || owned.draft().getSourceVersionId() != null
                || (updating(owned.skill())
                        && !Objects.equals(
                                owned.draft().getBaseSkillRevision(),
                                owned.skill().getRevision()))) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
    }

    /**
     * @return 固定配置；无法恢复来源时关闭更新，不猜默认分支
     */
    public SkillSourceDTO source(SkillDO skill) {
        SkillVersionDO creation =
                skill.getCreationVersionId() == null
                        ? null
                        : versions.selectById(skill.getCreationVersionId());
        SkillArtifactDO artifact =
                creation == null ? null : artifacts.selectById(creation.getArtifactId());
        if (creation == null
                || !skill.getSkillId().equals(creation.getSkillId())
                || !"RELEASE".equals(creation.getVersionType())
                || artifact == null
                || creation.getPublishedBy() == null
                || !creation.getPublishedBy().equals(artifact.getOwnerUserId())) {
            throw new ServerException("创建来源不完整，请联系管理员核验迁移", SkillErrorCodeEnum.UNAVAILABLE);
        }
        return codec.source(artifact.getSourceType(), artifact.getSourceJson());
    }

    /**
     * @return 服务端生成的精确引用请求，DEFAULT转首次实际分支
     */
    public SkillGithubSourceRequest request(SkillDO skill, Long revision) {
        SkillSourceDTO source = source(skill);
        if (!"GITHUB".equals(source.type())
                || source.repositoryId() == null
                || source.directory() == null
                || source.requestedRef() == null) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        String type =
                "DEFAULT".equals(source.resolvedRefType()) ? "BRANCH" : source.resolvedRefType();
        if (!Set.of("BRANCH", "TAG", "COMMIT").contains(type)) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        String ref = "COMMIT".equals(type) ? source.commitSha() : source.requestedRef();
        return github.parse(
                new SkillGithubImportCreateReqDTO(
                        revision, source.repositoryUrl(), type, ref, source.directory()));
    }

    /** 校验仓库稳定ID与固定引用，允许原分支实际SHA前进。 */
    public void validate(SkillDO skill, String type, String json) {
        if (!updating(skill)) {
            return;
        }
        SkillSourceDTO fixed = source(skill);
        if (!fixed.type().equals(type)) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        if ("GITHUB".equals(type)) {
            SkillSourceDTO actual = codec.source(type, json);
            SkillGithubSourceRequest expected = request(skill, 0L);
            String actualType =
                    "DEFAULT".equals(actual.resolvedRefType())
                            ? "BRANCH"
                            : actual.resolvedRefType();
            if (!Objects.equals(fixed.repositoryId(), actual.repositoryId())
                    || !Objects.equals(fixed.repositoryUrl(), actual.repositoryUrl())
                    || !expected.refType().equals(actualType)
                    || !expected.ref().equals(actual.requestedRef())
                    || !expected.directory().equals(actual.directory())) {
                throw new ClientException("创建来源已变化，请核验原仓库、引用和目录", SkillErrorCodeEnum.CONFLICT);
            }
        }
    }
}
