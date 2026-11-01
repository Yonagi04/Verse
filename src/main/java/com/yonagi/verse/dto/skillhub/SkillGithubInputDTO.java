package com.yonagi.verse.dto.skillhub;

/** 本人任务恢复用的安全来源输入，不包含用户凭据或任意请求头。 */
public record SkillGithubInputDTO(
        /** 已验证的公开仓库或tree地址。 */ String repositoryUrl,
        /** 用户请求的引用类型。 */ String refType,
        /** 用户请求的引用。 */ String ref,
        /** 用户请求的扫描目录。 */ String directory) {
}
