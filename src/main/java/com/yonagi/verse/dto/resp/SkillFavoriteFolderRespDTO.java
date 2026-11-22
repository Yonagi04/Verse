package com.yonagi.verse.dto.resp;

/** 个人收藏响应契约。 */
public record SkillFavoriteFolderRespDTO(
        /** 夹标识，默认夹为字符串 0。 */ String folderId,
        /** 夹展示名称。 */ String name,
        /** 是否为不可管理的默认夹。 */ Boolean defaultFolder,
        /** 夹中收藏数，包含失效项。 */ Long count) { }
