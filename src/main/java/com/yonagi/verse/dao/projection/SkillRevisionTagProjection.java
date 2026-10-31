package com.yonagi.verse.dao.projection;

import lombok.Getter;
import lombok.Setter;

/** 有界批量查询的修订标签关系。 */
@Getter
@Setter
public class SkillRevisionTagProjection {
    private Long revisionId;
    private String tagId;
}
