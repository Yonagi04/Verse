package com.yonagi.verse.common.cache;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 仅验证参数与依赖规范化算法，不模拟或证明 Redis、切面、锁或事务行为。 */
class QueryCacheKeyTest {
    private static final String TABLE = "t_items";
    private static final QueryDependency OWNER = new QueryDependency(TABLE, QueryScope.tenantUser(10L, 100L));

    @Test
    void unorderedMapsAndSetsHaveOneCanonicalRepresentation() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("tags", new LinkedHashSet<>(List.of("z", "a")));
        first.put("range", Map.of("to", 2, "from", 1));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("range", Map.of("from", 1, "to", 2));
        second.put("tags", new LinkedHashSet<>(List.of("a", "z")));

        assertEquals("{\"range\":{\"from\":1,\"to\":2},\"tags\":[\"a\",\"z\"]}",
                QueryCacheKey.parametersJson(first));
        assertEquals(QueryCacheKey.parametersJson(first), QueryCacheKey.parametersJson(second));
    }

    @Test
    void arraysAndListsPreserveBusinessOrderAndNullsRemainDistinct() {
        assertEquals("[2,1]", QueryCacheKey.parametersJson(new int[]{2, 1}));
        assertEquals("[]", QueryCacheKey.parametersJson(new long[0]));
        assertEquals("[null,\"x\"]", QueryCacheKey.parametersJson(new Object[]{null, "x"}));
        assertEquals("null", QueryCacheKey.parametersJson(null));
        assertNotEquals(QueryCacheKey.parametersJson(List.of("a", "b")),
                QueryCacheKey.parametersJson(List.of("b", "a")));
    }

    @Test
    void nestedRequestObjectsUseTheSameMapCanonicalization() {
        assertEquals("{\"filters\":{\"a\":1,\"z\":2}}",
                QueryCacheKey.parametersJson(new Search(Map.of("z", 2, "a", 1))));
    }

    @Test
    void dependencyOrderAndDuplicatesDoNotChangeTheCanonicalIdentitySet() {
        QueryDependency otherOwner = new QueryDependency(TABLE, QueryScope.tenantUser(10L, 200L));
        QueryDependency shared = QueryDependency.table("t_shared");
        List<QueryDependency> first = QueryCacheKey.dependencies(List.of(otherOwner, shared, OWNER, otherOwner),
                List.of(TABLE, "t_shared"), 64);
        List<QueryDependency> second = QueryCacheKey.dependencies(List.of(OWNER, otherOwner, shared),
                List.of("t_shared", TABLE), 64);

        assertEquals(List.of(OWNER, otherOwner, shared), first);
        assertEquals(first, second);
    }

    @Test
    void missingExtraAndMixedTableDependenciesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryCacheKey.dependencies(List.of(OWNER), List.of(TABLE, "t_shared"), 64));
        assertThrows(IllegalArgumentException.class, () -> QueryCacheKey.dependencies(
                List.of(OWNER, QueryDependency.table("t_extra")), List.of(TABLE), 64));
        assertThrows(IllegalArgumentException.class, () -> QueryCacheKey.dependencies(
                List.of(QueryDependency.table(TABLE), OWNER), List.of(TABLE), 64));
        assertThrows(IllegalArgumentException.class, () -> QueryCacheKey.dependencies(
                List.of(OWNER, QueryDependency.table(TABLE)), List.of(TABLE), 64));
    }

    @Test
    void emptyNullAndOversizedDeclarationsFailBeforeNormalization() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryCacheKey.dependencies(null, List.of(TABLE), 64));
        assertThrows(IllegalArgumentException.class,
                () -> QueryCacheKey.dependencies(List.of(), List.of(TABLE), 64));
        assertThrows(IllegalArgumentException.class,
                () -> QueryCacheKey.dependencies(Collections.nCopies(65, OWNER), List.of(TABLE), 64));
        assertThrows(IllegalArgumentException.class,
                () -> QueryCacheKey.dependencies(List.of(OWNER), null, 64));
        assertThrows(IllegalArgumentException.class,
                () -> QueryCacheKey.dependencies(List.of(OWNER), List.of(), 64));
        assertThrows(IllegalArgumentException.class,
                () -> QueryCacheKey.dependencies(Arrays.asList(OWNER, null), List.of(TABLE), 64));
    }

    @Test
    void businessDeclarationsCannotInjectInternalBroadcastResources() {
        assertThrows(IllegalArgumentException.class, () -> QueryCacheKey.dependencies(
                List.of(QueryDependency.broadcast(TABLE)), List.of(TABLE), 64));
    }

    @Test
    void multipleExplicitScopesOnOneTableRemainSeparateDependencies() {
        QueryDependency tenant = new QueryDependency(TABLE, QueryScope.tenant(10L));
        assertEquals(List.of(tenant, OWNER), QueryCacheKey.dependencies(
                List.of(OWNER, tenant), List.of(TABLE), 64));
    }

    @Test
    void resourceIdentitiesRoundTripAcrossSupportedOwnershipKinds() {
        List<String> identities = List.of("t_items", "b:t_items", "s:t_items:user:100",
                "s:t_items:tenant:10", "s:t_items:tenant:10:user:100",
                "s:t_items:user:9223372036854775807");
        for (String identity : identities) {
            assertEquals(identity, QueryDependency.fromIdentity(identity).identity());
        }
    }

    @Test
    void malformedRecoveryIdentitiesCannotAliasValidOwnerKeys() {
        List<String> invalid = List.of("", "t_", "T_items", "t_" + "a".repeat(63),
                "x:t_items", "b:t_items:extra", "x:t_items:user:1", "s:t_items:owner:1",
                "s:t_items:user:001", "s:t_items:user:0", "s:t_items:user:-1",
                "s:t_items:user:9223372036854775808", "s:t_items:tenant:1:owner:2");
        for (String identity : invalid) {
            assertThrows(IllegalArgumentException.class, () -> QueryDependency.fromIdentity(identity), identity);
        }
        assertThrows(NullPointerException.class, () -> QueryDependency.fromIdentity(null));
        assertThrows(IllegalArgumentException.class, () -> QueryDependency.table(null));
    }

    public record Search(
            /** 模拟具有嵌套筛选条件的读取参数。 */ Map<String, Object> filters) { }
}
