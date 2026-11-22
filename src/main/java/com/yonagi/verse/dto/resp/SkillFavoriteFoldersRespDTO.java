package com.yonagi.verse.dto.resp;

import java.util.List;

/** 个人收藏响应契约。 */
public record SkillFavoriteFoldersRespDTO(
        /** 全部可选夹，默认夹始终存在。 */ List<SkillFavoriteFolderRespDTO> folders,
        /** 未搜索的收藏总数。 */ Long total,
        /** 自定义夹数量上限。 */ Integer folderLimit,
        /** 收藏数量上限。 */ Integer favoriteLimit) { }
