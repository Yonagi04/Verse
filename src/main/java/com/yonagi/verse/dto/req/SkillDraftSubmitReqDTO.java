package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 提交只引用已保存草稿，不接收审批状态或身份。 */
public record SkillDraftSubmitReqDTO(
        /** 已检查并确认的草稿修订号。 */ @NotNull @PositiveOrZero Long revision) {
}
