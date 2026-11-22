package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.*;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.*;
import com.yonagi.verse.dao.mapper.skillhub.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 业务状态与拒绝语义；真实约束、锁和回滚在受控 MySQL 中独立演练。 */
class SkillFavoriteCommandServiceTest {
    private static final String KEY = "12345678-1234-1234-1234-123456789abc";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final LocalDateTime SAVED = LocalDateTime.of(2026, 11, 1, 1, 2, 3);
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final SkillFavoriteMapper favorites = mock(SkillFavoriteMapper.class);
    private final SkillFavoriteFolderMapper folders = mock(SkillFavoriteFolderMapper.class);
    private final SkillFavoriteQueryMapper queries = mock(SkillFavoriteQueryMapper.class);
    private final SkillIdempotencyService keys = mock(SkillIdempotencyService.class);
    private SkillFavoriteCommandService service;

    @BeforeEach void setup() {
        SkillTestFixture.initializeMapping(SkillFavoriteDO.class, SkillFavoriteFolderDO.class);
        service = at(NOW);
        when(accounts.lockActiveAccountExclusively(1L)).thenReturn(true);
        when(favorites.selectCount(any())).thenReturn(0L);
        when(folders.selectCount(any())).thenReturn(0L);
        when(queries.available(20L)).thenReturn(true);
        when(favorites.insert(any(SkillFavoriteDO.class))).thenReturn(1);
        when(folders.insert(any(SkillFavoriteFolderDO.class))).thenReturn(1);
        when(favorites.update(isNull(), any())).thenReturn(1);
        when(folders.update(isNull(), any())).thenReturn(1);
        when(favorites.delete(any())).thenReturn(1);
        when(folders.delete(any())).thenReturn(1);
    }

    @Test void createNormalizesFolderAndRecordsStableReceipt() {
        var result = service.createFolder(1L, "\u00A0DeV\uFEFF", KEY);
        assertTrue(result.created());
        ArgumentCaptor<SkillFavoriteFolderDO> row = ArgumentCaptor.forClass(SkillFavoriteFolderDO.class);
        verify(folders).insert(row.capture());
        assertEquals("DeV", row.getValue().getName());
        assertEquals("dev", row.getValue().getNameKey());
        assertEquals(1L, row.getValue().getUserId());
        assertEquals(result.receipt().folderId(), row.getValue().getFolderId().toString());
        verify(keys).record(1L, "FAV_FOLDER_CREATE", KEY, "DeV", row.getValue().getFolderId(), result.receipt());
    }

    @Test void folderCapacityDuplicateAndOwnershipRejectWithoutSuccessReceipt() {
        when(folders.selectCount(any())).thenReturn(20L);
        code(SkillErrorCodeEnum.LIMIT_EXCEEDED, () -> service.createFolder(1L, "Dev", KEY));
        when(folders.selectOne(any())).thenReturn(folder(10L, "Dev"));
        code(SkillErrorCodeEnum.CONFLICT, () -> service.createFolder(1L, "DEV", KEY));
        when(folders.selectOne(any())).thenReturn(null);
        code(SkillErrorCodeEnum.NOT_ACCESSIBLE, () -> service.renameFolder(1L, 10L, "New", KEY));
        code(SkillErrorCodeEnum.INVALID_INPUT, () -> service.deleteFolder(1L, 0L, KEY));
        code(SkillErrorCodeEnum.INVALID_INPUT, () -> service.renameFolder(1L, 0L, "New", KEY));
        verify(keys, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test void renameSelfNameIsNoopWhileNewNameChangesOnlyFolder() {
        when(folders.selectOne(any())).thenReturn(folder(10L, "Dev"));
        assertFalse(service.renameFolder(1L, 10L, "Dev", KEY).changed());
        assertTrue(service.renameFolder(1L, 10L, "New", KEY).changed());
        verify(folders).update(isNull(), any());
        verifyNoInteractions(favorites);
    }

    @Test void renameCollisionAndWriteFailureDoNotRecordSuccess() {
        when(folders.selectOne(any())).thenReturn(folder(10L, "Dev"), folder(11L, "New"));
        code(SkillErrorCodeEnum.CONFLICT, () -> service.renameFolder(1L, 10L, "New", KEY));
        when(folders.selectOne(any())).thenReturn(folder(10L, "Dev"), null);
        when(folders.update(isNull(), any())).thenReturn(0);
        assertThrows(ServerException.class, () -> service.renameFolder(1L, 10L, "New", KEY));
        verify(keys, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test void deleteReportsActualMigrationAndAbsentDeletionHasNoChange() {
        when(folders.selectOne(any())).thenReturn(folder(10L, "Dev"));
        when(favorites.update(isNull(), any())).thenReturn(7);
        var result = service.deleteFolder(1L, 10L, KEY);
        assertTrue(result.changed()); assertEquals(7, result.migrated());
        when(folders.selectOne(any())).thenReturn(null);
        result = service.deleteFolder(1L, 10L, KEY);
        assertFalse(result.changed()); assertEquals(0, result.migrated());
    }

    @Test void migrationDependencyFailureDoesNotReturnOrRecordSuccess() {
        when(folders.selectOne(any())).thenReturn(folder(10L, "Dev"));
        when(folders.delete(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.deleteFolder(1L, 10L, KEY));
        verify(keys, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test void createAt999AcceptsOneAndExistingKeepsOriginalFolderAndTime() {
        when(favorites.selectCount(any())).thenReturn(999L);
        var created = service.create(1L, 20L, 0L, KEY);
        assertTrue(created.created()); assertEquals("CREATED", created.receipt().outcome());
        ArgumentCaptor<SkillFavoriteDO> inserted = ArgumentCaptor.forClass(SkillFavoriteDO.class);
        verify(favorites).insert(inserted.capture());
        assertEquals(1L, inserted.getValue().getUserId()); assertEquals(20L, inserted.getValue().getSkillId());
        assertEquals(NOW, created.receipt().savedAt().toInstant());
        when(favorites.selectOne(any())).thenReturn(row());
        var existing = service.create(1L, 20L, 999L, KEY);
        assertFalse(existing.created()); assertEquals("EXISTING", existing.receipt().outcome());
        assertEquals("10", existing.receipt().folderId()); assertEquals(SAVED, existing.receipt().savedAt().toLocalDateTime());
    }

    @Test void newFavoriteRejectsFullMissingTargetOrUnavailableSource() {
        code(SkillErrorCodeEnum.NOT_ACCESSIBLE, () -> service.create(1L, 20L, 999L, KEY));
        when(favorites.selectCount(any())).thenReturn(1000L);
        code(SkillErrorCodeEnum.LIMIT_EXCEEDED, () -> service.create(1L, 20L, 0L, KEY));
        when(favorites.selectCount(any())).thenReturn(0L);
        when(queries.available(20L)).thenReturn(false);
        code(SkillErrorCodeEnum.NOT_ACCESSIBLE, () -> service.create(1L, 20L, 0L, KEY));
        verify(favorites, never()).insert(any(SkillFavoriteDO.class));
    }

    @Test void newFavoriteAndMoveAcceptAnOwnedCustomFolder() {
        when(folders.selectOne(any())).thenReturn(folder(10L, "Dev"));
        assertEquals("10", service.create(1L, 20L, 10L, KEY).receipt().folderId());
        SkillFavoriteDO current = row(); current.setFolderId(0L);
        when(favorites.selectOne(any())).thenReturn(current);
        assertEquals("10", service.move(1L, 100L, 10L, KEY).folderId());
        assertEquals(SAVED, current.getSavedAt());
    }

    @Test void invalidKeyAndInactiveActorCannotReadOrReplayPrivateState() {
        code(SkillErrorCodeEnum.INVALID_INPUT, () -> service.create(1L, 20L, 0L, "bad"));
        when(accounts.lockActiveAccountExclusively(1L)).thenReturn(false);
        code(SkillErrorCodeEnum.NOT_ACCESSIBLE, () -> service.cancel(1L, 100L, KEY));
        code(SkillErrorCodeEnum.NOT_ACCESSIBLE, () -> service.cancel(null, 100L, KEY));
        verifyNoInteractions(keys, favorites);
    }

    @Test void movingUnavailableFavoriteKeepsTimeAndRejectsStaleRelationOrTarget() {
        SkillFavoriteDO row = row();
        when(favorites.selectOne(any())).thenReturn(row);
        var result = service.move(1L, 100L, 0L, KEY);
        assertEquals("0", result.folderId()); assertEquals(SAVED, result.savedAt().toLocalDateTime());
        service.move(1L, 100L, 0L, KEY);
        verify(favorites).update(isNull(), any());
        code(SkillErrorCodeEnum.NOT_ACCESSIBLE, () -> service.move(1L, 100L, 999L, KEY));
        when(favorites.selectOne(any())).thenReturn(null);
        code(SkillErrorCodeEnum.CONFLICT, () -> service.move(1L, 100L, 0L, KEY));
        verifyNoInteractions(queries);
    }

    @Test void cancellationKeepsServerSnapshotAndAbsentRelationCannotUndo() {
        when(favorites.selectOne(any())).thenReturn(row());
        var receipt = service.cancel(1L, 100L, KEY);
        assertTrue(receipt.removed()); assertEquals("100", receipt.favoriteId()); assertEquals("20", receipt.skillId());
        assertEquals(SAVED, receipt.savedAt().toLocalDateTime());
        assertEquals(NOW.plusSeconds(30), receipt.undoExpiresAt().toInstant());
        verify(keys).record(1L, "FAV_CANCEL", KEY, 100L, 100L, receipt);
        when(favorites.selectOne(any())).thenReturn(null);
        var absent = service.cancel(1L, 100L, KEY);
        assertFalse(absent.removed()); assertNull(absent.undoExpiresAt()); assertNull(absent.skillId());
    }

    @Test void restorationUsesNewRelationIdOriginalTimeAndAllowsUnavailableSource() {
        previous(NOW.plusSeconds(30), true);
        when(folders.selectOne(any())).thenReturn(folder(10L, "Dev"));
        var result = service.restore(1L, 100L, KEY);
        assertEquals("RESTORED", result.outcome()); assertNotEquals("100", result.favoriteId());
        assertEquals("10", result.folderId()); assertEquals(SAVED, result.savedAt().toLocalDateTime());
        assertFalse(result.fallbackToDefault()); verifyNoInteractions(queries);
    }

    @Test void missingOriginalFolderFallsBackAndExistingNewFavoriteIsNeverOverwritten() {
        previous(NOW.plusSeconds(30), true);
        var result = service.restore(1L, 100L, KEY);
        assertTrue(result.fallbackToDefault()); assertEquals("0", result.folderId());
        SkillFavoriteDO newer = row(); newer.setFavoriteId(200L); newer.setFolderId(30L); newer.setSavedAt(SAVED.plusDays(1));
        when(favorites.selectOne(any())).thenReturn(newer);
        result = service.restore(1L, 100L, KEY);
        assertEquals("EXISTING", result.outcome()); assertEquals("200", result.favoriteId());
        assertEquals("30", result.folderId()); assertEquals(SAVED.plusDays(1), result.savedAt().toLocalDateTime());
        verify(favorites, times(1)).insert(any(SkillFavoriteDO.class));
    }

    @Test void deadlineIsInclusiveAndExpiredMissingOrNoopCancelRejects() {
        previous(NOW, true);
        assertEquals("RESTORED", service.restore(1L, 100L, KEY).outcome());
        code(SkillErrorCodeEnum.FAVORITE_UNDO_EXPIRED, () -> at(NOW.plusMillis(1)).restore(1L, 100L, KEY));
        previous(NOW.plusSeconds(30), false);
        code(SkillErrorCodeEnum.FAVORITE_UNDO_EXPIRED, () -> service.restore(1L, 100L, KEY));
        when(keys.replay(1L, "FAV_CANCEL", KEY, 100L, SkillFavoriteReceiptRespDTO.class)).thenReturn(null);
        code(SkillErrorCodeEnum.FAVORITE_UNDO_EXPIRED, () -> service.restore(1L, 100L, KEY));
    }

    @Test void restoreAtCapacityRejectsWithoutChangingExistingState() {
        previous(NOW.plusSeconds(30), true);
        when(favorites.selectCount(any())).thenReturn(1000L);
        code(SkillErrorCodeEnum.LIMIT_EXCEEDED, () -> service.restore(1L, 100L, KEY));
        verify(favorites, never()).insert(any(SkillFavoriteDO.class));
        verify(keys, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test void originalDefaultFolderRestoresWithoutCustomFolderLookupAndMissingDeadlineRejects() {
        var previous = new SkillFavoriteReceiptRespDTO("CANCELLED", "100", "20", "0",
                SAVED.atOffset(ZoneOffset.UTC), true, NOW.plusSeconds(30).atOffset(ZoneOffset.UTC), false);
        when(keys.replay(1L, "FAV_CANCEL", KEY, 100L, SkillFavoriteReceiptRespDTO.class)).thenReturn(previous);
        var restored = service.restore(1L, 100L, KEY);
        assertEquals("0", restored.folderId()); assertFalse(restored.fallbackToDefault());
        verifyNoInteractions(folders);
        when(keys.replay(1L, "FAV_CANCEL", KEY, 100L, SkillFavoriteReceiptRespDTO.class)).thenReturn(
                new SkillFavoriteReceiptRespDTO("CANCELLED", "100", "20", "0", previous.savedAt(), true, null, false));
        code(SkillErrorCodeEnum.FAVORITE_UNDO_EXPIRED, () -> service.restore(1L, 100L, KEY));
    }

    @Test void successfulRestoreReplayNeverRevivesAfterSecondCancellationEvenPastDeadline() {
        var receipt = new SkillFavoriteReceiptRespDTO("RESTORED", "200", "20", "0", SAVED.atOffset(ZoneOffset.UTC), false, null, true);
        when(keys.replay(1L, "FAV_RESTORE", KEY, 100L, SkillFavoriteReceiptRespDTO.class)).thenReturn(receipt);
        assertSame(receipt, at(NOW.plusSeconds(100)).restore(1L, 100L, KEY));
        verifyNoInteractions(favorites, folders, queries);
        verify(keys, never()).replay(1L, "FAV_CANCEL", KEY, 100L, SkillFavoriteReceiptRespDTO.class);
    }

    @Test void allOtherCommandsReplayBeforeCurrentResourceAndCapacityChecks() {
        var folderReceipt = new SkillFavoriteFolderReceiptRespDTO("10", true, 7);
        var receipt = new SkillFavoriteReceiptRespDTO("CREATED", "100", "20", "10", SAVED.atOffset(ZoneOffset.UTC), false, null, false);
        when(keys.replay(1L, "FAV_FOLDER_CREATE", KEY, "Dev", SkillFavoriteFolderReceiptRespDTO.class)).thenReturn(folderReceipt);
        when(keys.replay(1L, "FAV_FOLDER_RENAME", KEY, List.of(10L, "Dev"), SkillFavoriteFolderReceiptRespDTO.class)).thenReturn(folderReceipt);
        when(keys.replay(1L, "FAV_FOLDER_DELETE", KEY, 10L, SkillFavoriteFolderReceiptRespDTO.class)).thenReturn(folderReceipt);
        when(keys.replay(1L, "FAV_CREATE", KEY, List.of(20L, 0L), SkillFavoriteReceiptRespDTO.class)).thenReturn(receipt);
        when(keys.replay(1L, "FAV_MOVE", KEY, List.of(100L, 0L), SkillFavoriteReceiptRespDTO.class)).thenReturn(receipt);
        when(keys.replay(1L, "FAV_CANCEL", KEY, 100L, SkillFavoriteReceiptRespDTO.class)).thenReturn(receipt);
        assertFalse(service.createFolder(1L, "Dev", KEY).created());
        assertSame(folderReceipt, service.renameFolder(1L, 10L, "Dev", KEY));
        assertSame(folderReceipt, service.deleteFolder(1L, 10L, KEY));
        assertFalse(service.create(1L, 20L, 0L, KEY).created());
        assertSame(receipt, service.move(1L, 100L, 0L, KEY));
        assertSame(receipt, service.cancel(1L, 100L, KEY));
        verifyNoInteractions(favorites, folders, queries);
    }

    @Test void idempotencyConflictAndInsertFailurePropagateWithoutFakeSuccess() {
        when(keys.replay(eq(1L), eq("FAV_CREATE"), eq(KEY), any(), eq(SkillFavoriteReceiptRespDTO.class)))
                .thenThrow(new ClientException(SkillErrorCodeEnum.IDEMPOTENCY_CONFLICT));
        code(SkillErrorCodeEnum.IDEMPOTENCY_CONFLICT, () -> service.create(1L, 20L, 0L, KEY));
        reset(keys);
        when(favorites.insert(any(SkillFavoriteDO.class))).thenReturn(0);
        assertThrows(ServerException.class, () -> service.create(1L, 20L, 0L, KEY));
        verify(keys, never()).record(any(), any(), any(), any(), any(), any());
    }

    private SkillFavoriteCommandService at(Instant time) {
        return new SkillFavoriteCommandService(accounts, favorites, folders, queries, keys, Clock.fixed(time, ZoneOffset.UTC));
    }
    private void previous(Instant deadline, boolean removed) {
        when(keys.replay(1L, "FAV_CANCEL", KEY, 100L, SkillFavoriteReceiptRespDTO.class)).thenReturn(
                new SkillFavoriteReceiptRespDTO("CANCELLED", "100", "20", "10", SAVED.atOffset(ZoneOffset.UTC),
                        removed, deadline.atOffset(ZoneOffset.UTC), false));
    }
    private static SkillFavoriteFolderDO folder(Long id, String name) {
        SkillFavoriteFolderDO row = new SkillFavoriteFolderDO(); row.setFolderId(id); row.setUserId(1L); row.setName(name);
        return row;
    }
    private static SkillFavoriteDO row() {
        SkillFavoriteDO row = new SkillFavoriteDO(); row.setFavoriteId(100L); row.setUserId(1L); row.setSkillId(20L);
        row.setFolderId(10L); row.setSavedAt(SAVED); return row;
    }
    private static void code(SkillErrorCodeEnum code, Runnable operation) {
        assertEquals(code.code(), assertThrows(ClientException.class, operation::run).getErrorCode());
    }
}
