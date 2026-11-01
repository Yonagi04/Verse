package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 选择已由服务端解析的来源，不接受任意SHA替换。 */
public record SkillGithubSourceConfirmReqDTO(
        /** 已观察的草稿修订。 */ @NotNull @PositiveOrZero Long revision,
        /** 本任务来源选项标识。 */ @NotBlank @Size(max = 64) String sourceOptionId) {
}
