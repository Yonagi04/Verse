package com.yonagi.verse.dto.resp;

/** 当前公开版本的不可变文件元数据，不暴露对象存储位置。 */
public record SkillFileRespDTO(
        /** 包根目录下的相对路径。 */ String relativePath,
        /** 原文件字节数。 */ Long sizeBytes,
        /** 原文件内容摘要。 */ String sha256) {
}
