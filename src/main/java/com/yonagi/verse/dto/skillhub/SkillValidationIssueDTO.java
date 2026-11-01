package com.yonagi.verse.dto.skillhub;

/** 只提供定位与固定说明，不包含秘密片段或内部存储路径。 */
public record SkillValidationIssueDTO(
        /** 稳定机器原因。 */ String code,
        /** 仓库或所选Skill内的相对路径。 */ String path,
        /** 能安全确定时的一基行号。 */ Integer line,
        /** 有界安全说明。 */ String message) {
}
