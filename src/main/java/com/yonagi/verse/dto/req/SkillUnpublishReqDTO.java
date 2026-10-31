package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 作者主动下架，不能借此解除平台限制。 */
public record SkillUnpublishReqDTO(
        /** 当前Skill修订号。 */ @NotNull @PositiveOrZero Long revision,
        /** 明确记录的下架原因。 */ @NotBlank @Size(max = 300) String reason) {
}
