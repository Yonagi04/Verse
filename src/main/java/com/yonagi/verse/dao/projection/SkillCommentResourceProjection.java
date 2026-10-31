package com.yonagi.verse.dao.projection;

import lombok.Getter;
import lombok.Setter;

/** 评论授权所需的轻量Skill引用，不加载Markdown或包元数据。 */
@Getter
@Setter
public class SkillCommentResourceProjection {
    private Long skillId;
    private Long publisherId;
    private Long ownerUserId;
    private String name;
}
