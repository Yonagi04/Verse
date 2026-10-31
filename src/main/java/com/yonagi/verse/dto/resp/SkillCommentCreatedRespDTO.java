package com.yonagi.verse.dto.resp;


/** 发送回执仅含资源ID，删除后回放不泄露正文。 */
public record SkillCommentCreatedRespDTO(
        /** 新评论标识。 */ String commentId,
        /** 所属父评论标识，父评论为自身。 */ String rootId,
        /** 所属Skill标识。 */ String skillId) {
}
