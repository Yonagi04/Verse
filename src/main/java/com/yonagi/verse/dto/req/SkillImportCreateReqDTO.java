package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 为当前草稿创建一次可恢复ZIP导入。 */
public record SkillImportCreateReqDTO(
        /** 防止并发包覆盖的草稿修订。 */ @NotNull @PositiveOrZero Long revision) {
}
