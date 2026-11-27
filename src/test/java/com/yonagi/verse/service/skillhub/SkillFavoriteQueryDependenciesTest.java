package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.cache.QueryDependency;
import com.yonagi.verse.common.cache.QueryScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 只验证个人归属与共享数据的业务依赖选择，不模拟缓存和 Redis 行为。 */
class SkillFavoriteQueryDependenciesTest {
    private final SkillFavoriteQueryDependencies dependencies = new SkillFavoriteQueryDependencies();

    @Test
    void foldersAndStatusesBelongToTheSameAccountRegardlessOfSkillSelection() {
        List<QueryDependency> expected = List.of(
                new QueryDependency("t_skill_favorite", QueryScope.user(17L)),
                new QueryDependency("t_skill_favorite_folder", QueryScope.user(17L)));
        assertEquals(expected, dependencies.resolve(new Object[]{17L}));
        assertEquals(expected, dependencies.resolve(new Object[]{17L, List.of(20L, 21L)}));
        assertEquals(expected, dependencies.resolve(new Object[]{17L, List.of(99L)}));
        assertNotEquals(expected, dependencies.resolve(new Object[]{18L}));
    }

    @Test
    void pageKeepsPublicSourcesSharedWhileOnlyPrivateRelationsUseTheReader() {
        List<QueryDependency> actual = dependencies.resolve(new Object[]{17L,
                SkillFavoriteRules.condition(null, "Name", "updated", 1, 12)});
        assertEquals(9, actual.size());
        assertEquals(Set.of(
                new QueryDependency("t_skill_favorite", QueryScope.user(17L)),
                new QueryDependency("t_skill_favorite_folder", QueryScope.user(17L))),
                actual.stream().filter(dependency -> !dependency.isTable()).collect(Collectors.toSet()));
        assertEquals(Set.of("t_skill", "t_skill_revision", "t_skill_revision_tag", "t_skill_version",
                        "t_skill_artifact", "t_skill_publisher", "t_user"),
                actual.stream().filter(QueryDependency::isTable).map(QueryDependency::table)
                        .collect(Collectors.toSet()));
    }

    @Test
    void missingOrInvalidOwnerAndUnknownCallShapesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> dependencies.resolve(null));
        List<Object[]> invalid = List.of(new Object[]{}, new Object[]{null}, new Object[]{0L},
                new Object[]{-1L}, new Object[]{"17"}, new Object[]{17}, new Object[]{17L, null},
                new Object[]{17L, "condition"}, new Object[]{17L, List.of(), "extra"});
        for (Object[] arguments : invalid) {
            assertThrows(IllegalArgumentException.class, () -> dependencies.resolve(arguments));
        }
    }
}
