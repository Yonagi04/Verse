package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;
import java.util.List;

/** 仅含已批准公开资料的广场卡片。 */
public record SkillCardRespDTO(
        /** Skill业务标识。 */ String skillId,
        /** 平台公开作者。 */ SkillPublisherRespDTO publisher,
        /** 作者下固定的公开slug。 */ String slug,
        /** 已批准展示名。 */ String name,
        /** 已批准卡片简介。 */ String summary,
        /** 平台主分类。 */ String categoryId,
        /** 平台标签标识。 */ List<String> tags,
        /** 当前内容版本号。 */ String version,
        /** 当前固定版本标识。 */ String versionId,
        /** 当前版本批准时间。 */ OffsetDateTime publishedAt,
        /** 当前版本真实获取来源。 */ String sourceType) {
    /** 历史ZIP调用方保持兼容。 */
    public SkillCardRespDTO(String skillId, SkillPublisherRespDTO publisher, String slug, String name, String summary,
                            String categoryId, List<String> tags, String version, String versionId,
                            OffsetDateTime publishedAt) {
        this(skillId, publisher, slug, name, summary, categoryId, tags, version, versionId, publishedAt, "ZIP");
    }
}
