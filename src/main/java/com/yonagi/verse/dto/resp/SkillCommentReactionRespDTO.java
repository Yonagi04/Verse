package com.yonagi.verse.dto.resp;


/** 互动完成后的权威状态。 */
public record SkillCommentReactionRespDTO(
        /** 评论标识。 */ String commentId,
        /** 本人最终反应。 */ String reaction,
        /** 当前点赞数。 */ Long likeCount) {
}
