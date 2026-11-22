package com.yonagi.verse.dao.query;

/** 已规范化的收藏查询；全部参数参与缓存键，contains 已转义。 */
public record SkillFavoriteCondition(Long folderId, String keyword, String contains, String sort,
                                     int page, int size, int offset) { }
