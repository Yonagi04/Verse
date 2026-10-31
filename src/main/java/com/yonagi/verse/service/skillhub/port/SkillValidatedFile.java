package com.yonagi.verse.service.skillhub.port;

/** 子进程固定协议中的已校验文件清单。 */
public record SkillValidatedFile(String relativePath, String sha256, long sizeBytes) {
}
