package com.yonagi.verse.dto.resp;

/** 容量限制不代表环境功能开关。 */
public record SkillGithubLimitsRespDTO(
        /** 仓库压缩上限。 */ Long maxArchiveBytes,
        /** 仓库展开上限。 */ Long maxUnpackedBytes,
        /** 仓库条目上限。 */ Integer maxEntries,
        /** 单次候选上限。 */ Integer maxCandidates) {
}
