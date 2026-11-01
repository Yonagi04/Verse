package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 明确选择当前扫描中的一个完整Skill。 */
public record SkillGithubSelectionReqDTO(
        /** 已观察的草稿修订。 */ @NotNull @PositiveOrZero Long revision,
        /** 不可变扫描摘要。 */ @NotNull @Pattern(regexp = "[0-9a-f]{64}") String scanDigest,
        /** 服务端候选标识。 */ @NotBlank @Size(max = 64) String candidateId) {
}
