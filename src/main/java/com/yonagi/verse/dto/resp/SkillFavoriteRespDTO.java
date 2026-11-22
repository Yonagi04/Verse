package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;

/** 个人收藏响应契约。 */
public record SkillFavoriteRespDTO(
        /** 收藏关系标识。 */ String favoriteId,
        /** 稳定 Skill 标识。 */ String skillId,
        /** 当前归属夹。 */ String folderId,
        /** 当前夹名称。 */ String folderName,
        /** 原收藏时间，UTC。 */ OffsetDateTime savedAt,
        /** 当前公开可用性。 */ String availability,
        /** 当前公开卡片，失效时必须为空。 */ SkillCardRespDTO skill) { }
