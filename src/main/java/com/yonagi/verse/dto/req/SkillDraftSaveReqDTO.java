package com.yonagi.verse.dto.req;

import com.yonagi.verse.dto.SkillPublicationMetadataDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 保存本人草稿，不直接影响公开指针。 */
public record SkillDraftSaveReqDTO(
        /** 读取草稿时取得的修订号。 */ @NotNull @PositiveOrZero Long revision,
        /** 完整的待公开表单。 */ @NotNull @Valid SkillPublicationMetadataDTO metadata) {
}
