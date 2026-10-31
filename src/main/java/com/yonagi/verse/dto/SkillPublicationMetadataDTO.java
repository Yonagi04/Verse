package com.yonagi.verse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 发布表单和不可变声明快照的固定形状，不包含归属、存储或审批决定字段。 */
public record SkillPublicationMetadataDTO(
        /** 广场展示名，不重写包内name。 */ @NotBlank @Size(max = 80) String name,
        /** 广场卡片简介。 */ @NotBlank @Size(max = 300) String summary,
        /** 平台分类标识。 */ @NotBlank @Size(max = 32) String categoryId,
        /** 平台标签，最多五个。 */ @NotNull @Size(max = 5) List<@NotBlank @Size(max = 32) String> tags,
        /** 平台SemVer，不覆盖已发布版本。 */ @NotBlank @Size(max = 64) String version,
        /** 首版发布说明。 */ @NotBlank @Size(max = 2000) String releaseNotes,
        /** 作者确认的许可证或分发许可，草稿允许缺失，提交时校验。 */ @Size(max = 200) String license,
        /** 原作者，与平台发布者分开展示。 */ @NotBlank @Size(max = 100) String originalAuthor,
        /** 作者提供的可选来源。 */ @Size(max = 500) String sourceUrl,
        /** 作者声明的适用客户端。 */ @NotBlank @Size(max = 500) String compatibility,
        /** 依赖、环境变量名称和费用声明，不填写真实密钥。 */ @NotBlank @Size(max = 2000) String dependencies,
        /** 用户主动确认分发权。 */ @NotNull Boolean distributionAuthorized,
        /** 用户主动确认依赖声明。 */ @NotNull Boolean declarationsConfirmed) {
    public SkillPublicationMetadataDTO {
        if (tags != null) {
            tags = List.copyOf(tags);
        }
    }

    @Override
    public String toString() {
        return "SkillPublicationMetadataDTO[version=" + version + "]";
    }
}
