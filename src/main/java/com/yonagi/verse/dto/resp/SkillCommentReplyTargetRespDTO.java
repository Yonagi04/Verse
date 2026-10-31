package com.yonagi.verse.dto.resp;


/** 回复对象展示信息，不包含目标正文。 */
public record SkillCommentReplyTargetRespDTO(
        /** 实际被回复评论标识。 */ String commentId,
        /** 目标作者名称，已删除时为占位。 */ String displayName,
        /** 目标是否已删除。 */ Boolean deleted) {
}
