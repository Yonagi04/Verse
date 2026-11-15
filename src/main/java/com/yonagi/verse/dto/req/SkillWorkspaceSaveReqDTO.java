package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 后续更新仅允许修改本次说明。 */
public record SkillWorkspaceSaveReqDTO(
        /** 当前编辑修订。 */
        @NotNull @PositiveOrZero Long revision,
        /** 不覆盖草稿节点的发布说明。 */
        @NotBlank @Size(max = 2000) String releaseNotes) {}
