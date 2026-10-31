package com.yonagi.verse.dto.resp;

import java.util.List;

/** Skill领域统一有界分页结果。 */
public record SkillPageRespDTO<T>(
        /** 当前页，从一开始。 */ Integer page,
        /** 每页数量。 */ Integer size,
        /** 满足可见性条件的总数。 */ Long total,
        /** 本页内容，空结果使用空集合。 */ List<T> items) {
}
