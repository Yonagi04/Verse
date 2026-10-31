package com.yonagi.verse.dao.projection;

import lombok.Getter;
import lombok.Setter;

/** 分类或标签词表的持久化投影，不承担HTTP序列化契约。 */
@Getter
@Setter
public class SkillOptionProjection {
    private String id;
    private String name;
}
