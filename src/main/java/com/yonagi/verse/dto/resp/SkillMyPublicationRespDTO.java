package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;

/** 本人发布同时表达公开版本、草稿与导入任务。 */
public record SkillMyPublicationRespDTO(
        /** Skill标识。 */ String skillId,
        /** 本人草稿入口。 */ String draftId,
        /** 已批准展示名或尚未公开的slug。 */ String name,
        /** 公开slug。 */ String slug,
        /** Skill生命周期状态。 */ String status,
        /** 草稿状态。 */ String draftStatus,
        /** 当前导入任务状态。 */ String jobStatus,
        /** 当前公开版本。 */ String version,
        /** 下架所需修订。 */ Long revision,
        /** 最近修改时间。 */ OffsetDateTime updatedAt) {
}
