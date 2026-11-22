package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkillFavoriteRulesTest {
    @Test void namesUseNfcEcmaWhitespaceAndCodePoints() {
        assertEquals("é", SkillFavoriteRules.name("\uFEFF\u00A0e\u0301\u2029"));
        assertEquals("😀".repeat(20), SkillFavoriteRules.name("😀".repeat(20)));
        assertEquals("i\u0307", SkillFavoriteRules.nameKey("İ"));
        assertEquals("dev", SkillFavoriteRules.nameKey("DEV"));
        assertEquals("👩‍💻", SkillFavoriteRules.name("👩‍💻"));
    }

    @Test void namesRejectMissingReservedControlAndOversize() {
        for (String name : Arrays.asList(null, "", " \u00A0", "默认收藏夹", "全部收藏", "😀".repeat(21), "a\u0000b")) {
            assertThrows(ClientException.class, () -> SkillFavoriteRules.name(name), String.valueOf(name));
        }
    }

    @Test void queryNormalizesKeywordAndEscapesLiteralWildcards() {
        var query = SkillFavoriteRules.condition(0L, "  a!%_  ", "updated", 2, 24);
        assertEquals("a!%_", query.keyword());
        assertEquals("%a!!!%!_%", query.contains());
        assertEquals(24, query.offset());
        assertNull(SkillFavoriteRules.condition(null, " \uFEFF", "saved", 1, 12).keyword());
        assertEquals(80, SkillFavoriteRules.condition(null, "😀".repeat(80), "saved", 1, 12)
                .keyword().codePointCount(0, 160));
    }

    @Test void malformedQueriesAndOriginalBatchLimitsAreRejected() {
        assertThrows(ClientException.class, () -> SkillFavoriteRules.condition(-1L, null, "saved", 1, 12));
        assertThrows(ClientException.class, () -> SkillFavoriteRules.condition(null, null, null, 1, 12));
        assertThrows(ClientException.class, () -> SkillFavoriteRules.condition(null, null, "other", 1, 12));
        assertThrows(ClientException.class, () -> SkillFavoriteRules.condition(null, null, "saved", 0, 12));
        assertThrows(ClientException.class, () -> SkillFavoriteRules.condition(null, null, "saved", 10001, 12));
        assertThrows(ClientException.class, () -> SkillFavoriteRules.condition(null, null, "saved", 1, 13));
        assertThrows(ClientException.class, () -> SkillFavoriteRules.condition(null, "😀".repeat(81), "saved", 1, 12));
        assertEquals(List.of(1L, 2L), SkillFavoriteRules.statusIds(List.of(2L, 1L, 2L)));
        for (List<Long> ids : Arrays.asList(null, List.<Long>of(), Arrays.asList(1L, null), List.of(0L),
                Collections.nCopies(25, 1L))) {
            assertThrows(ClientException.class, () -> SkillFavoriteRules.statusIds(ids));
        }
        assertThrows(ClientException.class, () -> SkillFavoriteRules.folderId(null, false));
        assertThrows(ClientException.class, () -> SkillFavoriteRules.positiveId(null));
        assertThrows(ClientException.class, () -> SkillFavoriteRules.positiveId(0L));
    }
}
