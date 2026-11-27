package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.cache.QueryDependency;
import com.yonagi.verse.common.cache.QueryScope;
import com.yonagi.verse.dao.entity.skillhub.SkillFavoriteDO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 归属来自写 SQL 的显式参数，不从实体、操作者或请求线程猜测。 */
class SkillFavoriteMutationDependenciesTest {
    private static final String PREFIX = "com.yonagi.verse.dao.mapper.skillhub.";
    private final SkillFavoriteMutationDependencies dependencies = new SkillFavoriteMutationDependencies();

    @Test
    void everyFavoriteAndFolderWriteTargetsItsExplicitOwnerAndOwnTable() {
        Map<String, String> writes = Map.ofEntries(
                Map.entry("SkillFavoriteMapper.insertOwned", "t_skill_favorite"),
                Map.entry("SkillFavoriteMapper.moveOwned", "t_skill_favorite"),
                Map.entry("SkillFavoriteMapper.moveFolderToDefault", "t_skill_favorite"),
                Map.entry("SkillFavoriteMapper.deleteOwned", "t_skill_favorite"),
                Map.entry("SkillFavoriteMapper.deleteAllOwned", "t_skill_favorite"),
                Map.entry("SkillFavoriteFolderMapper.insertOwned", "t_skill_favorite_folder"),
                Map.entry("SkillFavoriteFolderMapper.renameOwned", "t_skill_favorite_folder"),
                Map.entry("SkillFavoriteFolderMapper.deleteOwned", "t_skill_favorite_folder"),
                Map.entry("SkillFavoriteFolderMapper.deleteAllOwned", "t_skill_favorite_folder"));
        writes.forEach((statement, table) -> assertEquals(
                List.of(new QueryDependency(table, QueryScope.user(17L))),
                dependencies.resolve(PREFIX + statement, table, Map.of("userId", 17L))));
    }

    @Test
    void entityAndActorCannotOverrideTheSqlOwner() {
        SkillFavoriteDO row = new SkillFavoriteDO();
        row.setUserId(99L);
        assertEquals(List.of(new QueryDependency("t_skill_favorite", QueryScope.user(17L))),
                dependencies.resolve(PREFIX + "SkillFavoriteMapper.insertOwned", "t_skill_favorite",
                        Map.of("userId", 17L, "row", row, "actorId", 88L)));
        assertEquals(List.of(new QueryDependency("t_skill_favorite", QueryScope.user(18L))),
                dependencies.resolve(PREFIX + "SkillFavoriteMapper.insertOwned", "t_skill_favorite",
                        Map.of("userId", 18L, "row", row)));
    }

    @Test
    void wrongStatementOrTargetCannotInvalidateUnrelatedResources() {
        for (String statement : new String[]{null, "", PREFIX + "SkillFavoriteMapper.insert"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> dependencies.resolve(statement, "t_skill_favorite", Map.of("userId", 17L)));
        }
        for (String table : new String[]{null, "t_user", "t_skill_favorite_folder"}) {
            assertThrows(IllegalArgumentException.class, () -> dependencies.resolve(
                    PREFIX + "SkillFavoriteMapper.insertOwned", table, Map.of("userId", 17L)));
        }
    }

    @Test
    void absentAndMalformedOwnerNeverFallBackToEntityOrBroadcast() {
        Map<String, Object> nullOwner = new HashMap<>();
        nullOwner.put("userId", null);
        List<Object> invalid = List.of(new SkillFavoriteDO(), Map.of(), Map.of("actorId", 17L), nullOwner,
                Map.of("userId", "17"), Map.of("userId", 17), Map.of("userId", 0L), Map.of("userId", -1L));
        for (Object parameter : invalid) {
            assertThrows(IllegalArgumentException.class, () -> dependencies.resolve(
                    PREFIX + "SkillFavoriteMapper.insertOwned", "t_skill_favorite", parameter));
        }
        assertThrows(IllegalArgumentException.class, () -> dependencies.resolve(
                PREFIX + "SkillFavoriteMapper.insertOwned", "t_skill_favorite", null));
    }
}
