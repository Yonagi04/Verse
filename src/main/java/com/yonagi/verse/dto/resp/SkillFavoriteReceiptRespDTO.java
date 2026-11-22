package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;

/** 个人收藏响应契约。 */
public record SkillFavoriteReceiptRespDTO(
        /** CREATED、EXISTING、MOVED、CANCELLED、ABSENT 或 RESTORED。 */ String outcome,
        /** 本次关系定位，取消回执保留旧标识。 */ String favoriteId,
        /** 稳定 Skill 标识，无原收藏时为空。 */ String skillId,
        /** 夹定位，不作为当前视图快照。 */ String folderId,
        /** 原收藏时间，供服务端恢复。 */ OffsetDateTime savedAt,
        /** 取消是否实际删除。 */ Boolean removed,
        /** 首次恢复的服务端截止时间。 */ OffsetDateTime undoExpiresAt,
        /** 恢复时原夹消失而回默认。 */ Boolean fallbackToDefault) { }
