package com.yonagi.verse.dto.resp;

import java.util.List;

/** 平台词表与固定上传限制，不承担环境或业务功能开关。 */
public record SkillOptionsRespDTO(
        /** 平台可用分类。 */ List<SkillOptionRespDTO> categories,
        /** 平台可用标签。 */ List<SkillOptionRespDTO> tags,
        /** ZIP实际字节上限。 */ Long maxArchiveBytes,
        /** 展开总字节上限。 */ Long maxUnpackedBytes,
        /** 文件数量上限。 */ Integer maxFiles,
        /** GitHub仓库容量，所选Skill仍用上传限制。 */ SkillGithubLimitsRespDTO githubLimits) {
    /** 既有ZIP调用方保持兼容。 */
    public SkillOptionsRespDTO(List<SkillOptionRespDTO> categories, List<SkillOptionRespDTO> tags,
                               Long maxArchiveBytes, Long maxUnpackedBytes, Integer maxFiles) {
        this(categories, tags, maxArchiveBytes, maxUnpackedBytes, maxFiles, null);
    }
}
