package com.yonagi.verse.dao.projection;

import lombok.Getter;
import lombok.Setter;

/** 按个人夹分组计数。 */
@Getter
@Setter
public class SkillFavoriteCountProjection {
    private Long folderId;
    private Long count;
}
