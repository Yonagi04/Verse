package com.yonagi.verse.dto.resp;

/** 单个已检查文件元数据。 */
public record SkillDraftFileRespDTO(
        /** Skill根下的相对路径。 */ String relativePath,
        /** 原文件字节数。 */ Long sizeBytes,
        /** 原文件SHA256。 */ String sha256) {
}
