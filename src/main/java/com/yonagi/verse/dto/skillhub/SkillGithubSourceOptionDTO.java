package com.yonagi.verse.dto.skillhub;

/** 待本人确认的服务端解析选项，不包含凭据。 */
public record SkillGithubSourceOptionDTO(
        /** 仅在当前解析快照内有效的选项标识。 */ String sourceOptionId,
        /** 将被冻结的公开来源。 */ SkillSourceDTO source,
        /** 固定提交的根tree标识。 */ String treeSha) {
}
