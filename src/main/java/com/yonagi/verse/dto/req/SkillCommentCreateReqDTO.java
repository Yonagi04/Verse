package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.*;

/** 发送评论请求；最终码点长度在业务边界校验。 */
public record SkillCommentCreateReqDTO(
        /** 评论纯文本。 */ @NotBlank @Size(max = 2000) String content,
        /** 实际回复目标，不传则为父评论。 */ @Positive Long replyToCommentId) {
}
