package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.*;

/** 个人收藏请求契约。 */
public record SkillFavoriteFolderReqDTO(
        /** 收藏夹名称，码点规则由领域校验。 */ @NotNull @Size(max = 160) String name) { }
