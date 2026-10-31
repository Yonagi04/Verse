package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 创建首版草稿，P1不支持覆盖或修改既有版本。 */
public record SkillDraftCreateReqDTO(
        /** 同平台作者下唯一的稳定slug。 */
        @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug) {
}
