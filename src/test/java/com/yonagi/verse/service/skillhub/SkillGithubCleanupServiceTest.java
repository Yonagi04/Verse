package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.dao.entity.skillhub.SkillGithubSourceDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillGithubSourceMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅验证清理资格与保留策略；不模拟证明S3删除、SQL条件或事务真实正确。 */
class SkillGithubCleanupServiceTest {
    private final SkillImportTaskMapper tasks = mock(SkillImportTaskMapper.class);
    private final SkillGithubSourceMapper sources = mock(SkillGithubSourceMapper.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final SkillGithubSnapshotCodec codec = new SkillGithubSnapshotCodec(new com.fasterxml.jackson.databind.ObjectMapper());
    private final SkillGithubCleanupService service = new SkillGithubCleanupService(tasks, sources, drafts, accounts, codec);
    private final SkillImportTaskDO task = SkillTestFixture.task();
    private final SkillDraftDO draft = SkillTestFixture.draft();
    private final SkillGithubSourceDO source = new SkillGithubSourceDO();

    @BeforeEach
    void setup() {
        SkillTestFixture.initializeMapping(SkillImportTaskDO.class, SkillGithubSourceDO.class);
        task.setSourceType("GITHUB"); task.setStatus("SUCCEEDED"); task.setSourceKey("archive");
        source.setJobId(50L); source.setTreeKey("tree");
        when(tasks.selectByIdForUpdate(50L)).thenReturn(task);
        when(sources.selectById(50L)).thenReturn(source);
        when(drafts.selectById(30L)).thenReturn(draft);
        when(accounts.isAccountActive(1L)).thenReturn(true);
    }

    @Test
    void liveTaskAndSevenDayRecoveryWindowProtectSourceBytes() {
        for (String status : List.of("RUNNING", "WAITING_SELECTION", "WAITING_CONFIRMATION", "RETRY_WAIT")) {
            task.setStatus(status); assertTrue(service.keys(50L).isEmpty());
        }
        task.setStatus("FAILED"); assertTrue(service.keys(50L).isEmpty());
        task.setUpdateTime(SkillTestFixture.now().minusDays(8));
        assertEquals(List.of("archive", "tree"), service.keys(50L));
        task.setSourceType("ZIP"); assertTrue(service.keys(50L).isEmpty());
    }

    @Test
    void closedAccountAndAbandonedDraftMayCleanTerminalBytesBeforeSevenDays() {
        when(accounts.isAccountActive(1L)).thenReturn(false);
        assertEquals(List.of("archive", "tree"), service.keys(50L));
        when(accounts.isAccountActive(1L)).thenReturn(true); draft.setStatus("CANCELLED");
        assertEquals(List.of("archive", "tree"), service.keys(50L));
        task.setSourceKey(null); source.setTreeKey(null); assertTrue(service.keys(50L).isEmpty());
    }

    @Test
    void onlyExpiredHumanWaitBecomesTerminalAndCannotExtendDeadline() {
        task.setStatus("WAITING_SELECTION"); task.setWaitUntil(SkillTestFixture.now().plusHours(1));
        service.expire(50L); assertEquals("WAITING_SELECTION", task.getStatus());
        task.setWaitUntil(SkillTestFixture.now().minusSeconds(1));
        var original = task.getWaitUntil(); service.expire(50L);
        assertEquals("FAILED", task.getStatus()); assertEquals("SOURCE_EXPIRED", task.getErrorCode());
        assertEquals(original, task.getWaitUntil());
        service.expire(50L); verify(tasks, times(1)).updateById(task);
    }

    @Test
    void pruningCandidatesRetainsSelectedDirectoryAndFixedCommitForFailureRetry() {
        task.setSourceKey(null); source.setTreeKey(null); source.setSelectedCandidateId("one");
        var origin = new SkillSourceDTO(1,"GITHUB","https://github.com/a/b","99","main","BRANCH","a".repeat(40),"skills");
        source.setConfirmedSourceJson(codec.encode(new SkillGithubSourceOptionDTO("option",origin,"b".repeat(40)),8192));
        source.setCandidatesJson(codec.encode(List.of(new SkillGithubCandidateDTO("one","skills/one","one","说明",1,10L,true,List.of())),8192));
        service.prune(50L);
        var retained = codec.decode(source.getConfirmedSourceJson(), SkillGithubSourceOptionDTO.class);
        assertEquals("skills/one",retained.source().directory()); assertEquals("a".repeat(40),retained.source().commitSha());
        assertEquals("b".repeat(40),retained.treeSha()); assertEquals("option",retained.sourceOptionId());
        source.setCandidatesJson(null); service.prune(50L);
        assertEquals(retained,codec.decode(source.getConfirmedSourceJson(), SkillGithubSourceOptionDTO.class));
    }

    @Test
    void pruningUnselectedSourceKeepsScanDirectoryAndSelectedRootKeepsEmptyDirectory() {
        task.setSourceKey(null); source.setTreeKey(null);
        var origin = new SkillSourceDTO(1,"GITHUB","https://github.com/a/b","99","main","BRANCH","a".repeat(40),"");
        var confirmed = new SkillGithubSourceOptionDTO("option",origin,"b".repeat(40));
        source.setConfirmedSourceJson(codec.encode(confirmed,8192));
        service.prune(50L); assertEquals(confirmed,codec.decode(source.getConfirmedSourceJson(), SkillGithubSourceOptionDTO.class));
        source.setSelectedCandidateId("root");
        source.setCandidatesJson(codec.encode(List.of(new SkillGithubCandidateDTO("root","","one","说明",1,10L,true,List.of())),8192));
        service.prune(50L); assertEquals(confirmed,codec.decode(source.getConfirmedSourceJson(), SkillGithubSourceOptionDTO.class));
    }

    @Test
    void sourceSummarySurvivesUntilRetentionEndsAndNeverPrunesWhileBytesRemain() {
        service.prune(50L); verify(sources, never()).deleteById(anyLong());
        verify(sources, never()).update(isNull(), any());
        task.setSourceKey(null); source.setTreeKey(null);
        service.prune(50L); verify(sources, never()).deleteById(anyLong());
        verify(sources).update(isNull(), any());
        task.setUpdateTime(SkillTestFixture.now().minusDays(31)); service.prune(50L);
        verify(sources).deleteById(50L);
    }
}
