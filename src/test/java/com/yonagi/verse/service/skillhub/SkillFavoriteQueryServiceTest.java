package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.skillhub.*;
import com.yonagi.verse.dao.mapper.skillhub.*;
import com.yonagi.verse.dao.projection.*;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillFavoriteQueryServiceTest {
    private final SkillFavoriteFolderMapper folders = mock(SkillFavoriteFolderMapper.class);
    private final SkillFavoriteQueryMapper queries = mock(SkillFavoriteQueryMapper.class);
    private final SkillPublicQueryMapper publicQueries = mock(SkillPublicQueryMapper.class);
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final SkillFavoriteQueryAccess access = new SkillFavoriteQueryAccess(accounts, folders);
    private final SkillFavoriteQueryService service = new SkillFavoriteQueryService(folders, queries, publicQueries, access);

    @BeforeEach void setup() {
        SkillTestFixture.initializeMapping(SkillFavoriteFolderDO.class);
        when(accounts.isAccountActive(1L)).thenReturn(true);
        when(folders.selectList(any())).thenReturn(List.of());
        when(queries.counts(1L)).thenReturn(List.of());
    }

    @Test void emptyAccountHasLogicalDefaultWithoutWrites() {
        var result = service.folders(1L);
        assertEquals(0L, result.total()); assertEquals(1, result.folders().size());
        assertEquals("0", result.folders().getFirst().folderId()); assertTrue(result.folders().getFirst().defaultFolder());
        assertEquals(0L, result.folders().getFirst().count()); assertEquals(20, result.folderLimit());
        assertEquals(1000, result.favoriteLimit()); verify(folders, never()).insert(any(SkillFavoriteFolderDO.class));
    }

    @Test void folderCountsIncludeUnavailableRelationsAndEmptyCustomFolders() {
        SkillFavoriteFolderDO folder = new SkillFavoriteFolderDO(); folder.setFolderId(10L); folder.setName("Dev");
        when(folders.selectList(any())).thenReturn(List.of(folder));
        SkillFavoriteCountProjection count = new SkillFavoriteCountProjection(); count.setFolderId(0L); count.setCount(8L);
        when(queries.counts(1L)).thenReturn(List.of(count));
        var result = service.folders(1L);
        assertEquals(8L, result.total()); assertEquals(8L, result.folders().getFirst().count());
        assertEquals(0L, result.folders().get(1).count()); assertEquals("Dev", result.folders().get(1).name());
    }

    @Test void statusesExplicitlyRepresentUnsavedAndNormalizeOrder() {
        SkillFavoriteProjection row = favorite();
        when(queries.statuses(1L, List.of(20L, 21L))).thenReturn(List.of(row));
        var result = service.statuses(1L, List.of(21L, 20L, 20L));
        assertEquals("100", result.getFirst().favoriteId()); assertEquals("Dev", result.getFirst().folderName());
        assertEquals("21", result.get(1).skillId()); assertNull(result.get(1).favoriteId());
        assertNull(result.get(1).savedAt()); assertEquals("Z", result.getFirst().savedAt().getOffset().toString());
    }

    @Test void unavailableResponseNeverReturnsEvenAccidentallyPopulatedSourceFields() {
        SkillFavoriteProjection row = favorite(); row.setAvailable(false); row.setSkill(publicCard());
        var condition = SkillFavoriteRules.condition(null, null, "updated", 1, 12);
        when(queries.count(1L, condition)).thenReturn(1L); when(queries.page(1L, condition)).thenReturn(List.of(row));
        var response = service.page(1L, condition);
        assertEquals(1L, response.total()); assertNull(response.items().getFirst().skill());
        assertEquals("UNAVAILABLE", response.items().getFirst().availability());
        assertEquals("Dev", response.items().getFirst().folderName()); verifyNoInteractions(publicQueries);
    }

    @Test void availableCardsUseCurrentRevisionBatchTagsAndNullableReleaseDate() {
        SkillFavoriteProjection row = favorite(); row.setAvailable(true); row.setSkill(publicCard());
        var condition = SkillFavoriteRules.condition(null, "Name", "saved", 1, 12);
        when(queries.count(1L, condition)).thenReturn(1L); when(queries.page(1L, condition)).thenReturn(List.of(row));
        SkillRevisionTagProjection tag = new SkillRevisionTagProjection(); tag.setRevisionId(30L); tag.setTagId("coding");
        when(publicQueries.selectRevisionTags(List.of(30L))).thenReturn(List.of(tag));
        var card = service.page(1L, condition).items().getFirst().skill();
        assertEquals("Name", card.name()); assertEquals("v2", card.version()); assertEquals("40", card.versionId());
        assertEquals(List.of("coding"), card.tags()); assertNull(card.publishedAt());
        assertEquals("Owner", card.publisher().publicName());
    }

    @Test void zeroCountSkipsPageAndOutOfRangeRemainsEmpty() {
        var condition = SkillFavoriteRules.condition(null, null, "saved", 100, 12);
        assertEquals(List.of(), service.page(1L, condition).items()); verify(queries, never()).page(any(), any());
        when(queries.count(1L, condition)).thenReturn(2L); when(queries.page(1L, condition)).thenReturn(List.of());
        var result = service.page(1L, condition); assertEquals(100, result.page()); assertEquals(2L, result.total());
        assertTrue(result.items().isEmpty()); verifyNoInteractions(publicQueries);
    }

    @Test void inactiveAccountAndForeignOrDeletedFolderDenyBeforePrivateRead() {
        assertThrows(ClientException.class, () -> service.folders(2L));
        assertThrows(ClientException.class, () -> access.check(new Object[]{null}));
        assertThrows(ClientException.class, () -> service.page(1L, SkillFavoriteRules.condition(99L, null, "saved", 1, 12)));
        verifyNoInteractions(queries);
        SkillFavoriteFolderDO own = new SkillFavoriteFolderDO(); own.setFolderId(10L); own.setUserId(1L);
        when(folders.selectOne(any())).thenReturn(own);
        access.check(new Object[]{1L, SkillFavoriteRules.condition(10L, null, "saved", 1, 12)});
        when(folders.selectOne(any())).thenReturn(null);
        assertThrows(ClientException.class, () -> access.check(new Object[]{1L, SkillFavoriteRules.condition(10L, null, "saved", 1, 12)}));
        access.check(new Object[]{1L, List.of(20L)});
    }

    private static SkillFavoriteProjection favorite() {
        SkillFavoriteProjection row = new SkillFavoriteProjection(); row.setFavoriteId(100L); row.setSkillId(20L);
        row.setFolderId(10L); row.setFolderName("Dev"); row.setSavedAt(LocalDateTime.of(2026, 11, 1, 0, 0)); return row;
    }
    private static SkillPublicProjection publicCard() {
        SkillPublicProjection card = new SkillPublicProjection(); card.setSkillId(20L); card.setPublisherId(10L);
        card.setHandle("owner"); card.setPublicName("Owner"); card.setSlug("sample"); card.setName("Name");
        card.setSummary("Summary"); card.setCategoryId("development"); card.setRevisionId(30L);
        card.setVersionId(40L); card.setVersionLabel("v2"); card.setSourceType("ZIP"); return card;
    }
}
