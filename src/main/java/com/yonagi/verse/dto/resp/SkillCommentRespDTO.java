package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;

/** 评论视图，不暴露点踩总数或他人反应。 */
public record SkillCommentRespDTO(
        /** 评论标识。 */ String commentId,
        /** 所属父评论标识，父评论为自身。 */ String rootId,
        /** 实际回复目标，父评论为空。 */ String replyToCommentId,
        /** 当前作者公开资料，删除占位为空。 */ UserPublicProfileRespDTO author,
        /** 正文，删除占位为空。 */ String content,
        /** UTC创建时间。 */ OffsetDateTime createdAt,
        /** 点赞总数，占位为0。 */ Long likeCount,
        /** 本人赞踩状态。 */ String myReaction,
        /** 有效后代数。 */ Long replyCount,
        /** 是否为已删除占位。 */ Boolean deleted,
        /** 当前主体是否可删除。 */ Boolean canDelete,
        /** 当前条目是否可回复。 */ Boolean canReply,
        /** 实际目标展示信息。 */ SkillCommentReplyTargetRespDTO replyTo) {
}
