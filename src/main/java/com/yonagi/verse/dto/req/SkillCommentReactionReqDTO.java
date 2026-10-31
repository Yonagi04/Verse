package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.*;

/** 设置反应，重复请求不重复计数。 */
public record SkillCommentReactionReqDTO(
        /** 期望的最终反应状态。 */ @NotNull @Pattern(regexp = "LIKE|DISLIKE|NONE") String reaction) {
}
