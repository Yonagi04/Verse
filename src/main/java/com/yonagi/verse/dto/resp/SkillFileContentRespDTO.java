package com.yonagi.verse.dto.resp;

/** 只读文件预览，未就绪时不返回正文。 */
public record SkillFileContentRespDTO(
        /** 正文所属固定版本。 */ String versionId,
        /** 包根目录下的相对路径。 */ String relativePath,
        /** 原文件字节数。 */ Long sizeBytes,
        /** 原文件内容摘要。 */ String sha256,
        /** TEXT、MARKDOWN、CODE 或 UNSUPPORTED。 */ String kind,
        /** 对应语法高亮语言，普通文本和不支持类型为空。 */ String language,
        /** READY、UNSUPPORTED 或 TOO_LARGE。 */ String status,
        /** 只读 UTF-8 正文；不支持或过大时为空。 */ String content) {
}
