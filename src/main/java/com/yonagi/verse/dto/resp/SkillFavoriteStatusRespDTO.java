package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;

/** 个人收藏响应契约。 */
public record SkillFavoriteStatusRespDTO(
        /** 稳定 Skill 标识。 */ String skillId,
        /** 收藏关系标识，未收藏为空。 */ String favoriteId,
        /** 当前夹标识。 */ String folderId,
        /** 当前夹名称。 */ String folderName,
        /** 原收藏时间，UTC。 */ OffsetDateTime savedAt) { }
