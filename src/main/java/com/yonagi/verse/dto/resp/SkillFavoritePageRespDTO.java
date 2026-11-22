package com.yonagi.verse.dto.resp;

import java.util.List;

/** 收藏缓存采用具体元素类型，避免现有序列化器丢失泛型 Record 的元素信息。 */
public record SkillFavoritePageRespDTO(
        /** 当前页，从一开始。 */ Integer page,
        /** 每页数量。 */ Integer size,
        /** 当前范围搜索命中数，含无关键词时的失效关系。 */ Long total,
        /** 本页收藏，不包含失效源内容。 */ List<SkillFavoriteRespDTO> items) { }
