package com.yonagi.verse.dto.resp;

/** 个人收藏响应契约。 */
public record SkillFavoriteFolderReceiptRespDTO(
        /** 操作定位夹。 */ String folderId,
        /** 是否实际改变夹。 */ Boolean changed,
        /** 删除时实际迁移数。 */ Integer migrated) { }
