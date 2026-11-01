package com.yonagi.verse.dao.query;

import java.util.List;

/** 已归一化且有界的SQL参数，排序值只来自用例白名单。 */
public record SkillSearchCondition(String keyword, String exact, String prefix, String contains,
                                   String category, List<String> tags, Long publisherId,
                                   boolean relevance, int page, int size, int offset, String source) {
}
