package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.*;

/** 个人收藏请求契约。 */
public record SkillFavoriteMoveReqDTO(
        /** 目标夹，0 为默认夹。 */ @NotNull @PositiveOrZero Long folderId) { }
