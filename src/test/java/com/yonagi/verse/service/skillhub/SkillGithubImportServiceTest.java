package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.*;
import com.yonagi.verse.dao.mapper.skillhub.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillGithubImportServiceTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final SkillImportTaskService tasks = mock(SkillImportTaskService.class);
    private final SkillImportTaskMapper taskMapper = mock(SkillImportTaskMapper.class);
    private final SkillGithubSourceMapper sources = mock(SkillGithubSourceMapper.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final SkillGithubSnapshotCodec codec = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillIdempotencyService commands = mock(SkillIdempotencyService.class);
    private final SkillGithubImportService service = new SkillGithubImportService(access, tasks, taskMapper,
            sources, drafts, new SkillGithubSourcePolicy(), codec, commands, VALIDATORS.getValidator());
    private final SkillDraftDO draft = SkillTestFixture.draft();
    private final SkillImportTaskDO task = SkillTestFixture.task();
    private final SkillGithubSourceDO source = new SkillGithubSourceDO();
    private final SkillGithubSourceOptionDTO option = new SkillGithubSourceOptionDTO("option",
            new SkillSourceDTO(1, "GITHUB", "https://github.com/owner/repo", "99", "main", "BRANCH",
                    "a".repeat(40), "skills"), "b".repeat(40));

    @BeforeEach
    void ownership() {
        SkillTestFixture.initializeMapping(SkillDraftDO.class, SkillImportTaskDO.class, SkillGithubSourceDO.class);
        draft.setLatestJobId(50L);
        task.setSourceType("GITHUB"); task.setPhase("RESOLVE"); task.setStatus("WAITING_CONFIRMATION");
        task.setWaitUntil(SkillTestFixture.now().plusDays(1));
        source.setJobId(50L); source.setSourceOptionsJson(codec.encode(List.of(option), 32768));
        when(access.lockDraft(1L, 30L)).thenReturn(new SkillOwnedDraft(SkillTestFixture.publisher(), SkillTestFixture.skill(), draft));
        when(access.readDraft(1L, 30L)).thenReturn(new SkillOwnedDraft(SkillTestFixture.publisher(), SkillTestFixture.skill(), draft));
        when(taskMapper.selectOne(any())).thenReturn(task); when(taskMapper.selectByIdForUpdate(50L)).thenReturn(task);
        when(sources.selectById(50L)).thenReturn(source);
        when(tasks.get(1L, 50L)).thenReturn(new SkillImportTaskRespDTO("50", "30", "QUEUED", 3L, null, null, 0,
                SkillTestFixture.now().atOffset(java.time.ZoneOffset.UTC)));
    }

    @AfterAll static void closeValidator() { VALIDATORS.close(); }

    @Test
    void creationStoresOnlyValidatedPublicSourceAndReplayedTaskDoesNotOverwriteOriginal() {
        when(tasks.createGithub(eq(1L), eq(30L), eq(2L), eq("key"), any())).thenReturn(50L);
        service.create(1L, 30L, new SkillGithubImportCreateReqDTO(2L, "https://github.com/owner/repo", null, null, null), "key");
        verify(sources, never()).insert(any(SkillGithubSourceDO.class));
        assertEquals("a".repeat(40), codec.list(source.getSourceOptionsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<SkillGithubSourceOptionDTO>>() { }).getFirst().source().commitSha());
        assertThrows(ClientException.class, () -> service.create(1L, 30L,
                new SkillGithubImportCreateReqDTO(2L, "https://user:token@github.com/owner/repo", null, null, null), "bad"));
    }

    @Test
    void confirmsOnlyServerOptionAndFreezesItsShaWhileIncreasingDraftRevision() {
        service.confirm(1L, 50L, new SkillGithubSourceConfirmReqDTO(2L, "option"), "key");
        assertEquals(option, codec.decode(source.getConfirmedSourceJson(), SkillGithubSourceOptionDTO.class));
        assertEquals(3L, draft.getRevision());
        verify(commands).record(1L, "CONFIRM_GITHUB_SOURCE", "key",
                List.of(50L, new SkillGithubSourceConfirmReqDTO(2L, "option")), 50L, 50L);
    }

    @Test
    void replayPrecedesWaitingStateAndRevisionChecksWithoutChangingFrozenSource() {
        task.setStatus("SUCCEEDED"); draft.setRevision(20L);
        var input = new SkillGithubSourceConfirmReqDTO(2L, "option");
        when(commands.replay(1L, "CONFIRM_GITHUB_SOURCE", "key", List.of(50L, input), Long.class)).thenReturn(50L);
        assertEquals("50", service.confirm(1L, 50L, input, "key").jobId());
        assertNull(source.getConfirmedSourceJson()); assertEquals(20L, draft.getRevision());
        verify(sources, never()).updateById(any(SkillGithubSourceDO.class));
    }

    @Test
    void wrongOptionStaleRevisionExpiredOrForeignTaskNeverFreezesAnything() {
        assertThrows(ClientException.class, () -> service.confirm(1L, 50L, new SkillGithubSourceConfirmReqDTO(2L, "forged"), "key"));
        assertThrows(ClientException.class, () -> service.confirm(1L, 50L, new SkillGithubSourceConfirmReqDTO(1L, "option"), "key"));
        task.setWaitUntil(SkillTestFixture.now().minusSeconds(1));
        assertThrows(ClientException.class, () -> service.confirm(1L, 50L, new SkillGithubSourceConfirmReqDTO(2L, "option"), "key"));
        when(taskMapper.selectOne(any())).thenReturn(null);
        ClientException denied = assertThrows(ClientException.class,
                () -> service.confirm(2L, 50L, new SkillGithubSourceConfirmReqDTO(2L, "option"), "key"));
        assertEquals(SkillErrorCodeEnum.NOT_ACCESSIBLE.code(), denied.getErrorCode());
        assertNull(source.getConfirmedSourceJson()); assertEquals(2L, draft.getRevision());
    }

    @Test
    void selectionMustBelongToCurrentScanAndBeSelectable() {
        task.setStatus("WAITING_SELECTION"); task.setPhase("SELECT");
        source.setScanDigest("c".repeat(64));
        var valid = new SkillGithubCandidateDTO("valid", "skills/one", "one", "说明", 1, 10L, true, List.of());
        var bad = new SkillGithubCandidateDTO("bad", "skills/bad", null, null, 1, 10L, false, List.of());
        source.setCandidatesJson(codec.encode(List.of(valid, bad), 256 * 1024));
        assertThrows(ClientException.class, () -> service.select(1L, 50L,
                new SkillGithubSelectionReqDTO(2L, "d".repeat(64), "valid"), "stale"));
        assertThrows(ClientException.class, () -> service.select(1L, 50L,
                new SkillGithubSelectionReqDTO(2L, "c".repeat(64), "bad"), "bad"));
        service.select(1L, 50L, new SkillGithubSelectionReqDTO(2L, "c".repeat(64), "valid"), "key");
        assertEquals("valid", source.getSelectedCandidateId()); assertEquals(3L, draft.getRevision());
        assertEquals("a".repeat(40), option.source().commitSha());
    }

    @Test
    void candidatePaginationIsStableAndCannotLoadUnboundedResults() {
        source.setCandidatesJson(codec.encode(List.of(new SkillGithubCandidateDTO("one", "skills/one", "one", "说明",
                1, 10L, true, List.of())), 256 * 1024));
        source.setScanDigest("c".repeat(64));
        var result = service.candidates(1L, 50L, 1, 20);
        assertEquals(1, result.total()); assertEquals("skills/one", result.items().getFirst().directory());
        assertEquals("c".repeat(64), result.scanDigest());
        assertTrue(service.candidates(1L, 50L, 2, 20).items().isEmpty());
        assertThrows(ClientException.class, () -> service.candidates(1L, 50L, 1, 500));
    }
}
