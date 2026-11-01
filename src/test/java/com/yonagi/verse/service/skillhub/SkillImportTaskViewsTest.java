package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillGithubSourceDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillGithubSourceMapper;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import com.yonagi.verse.dto.skillhub.SkillValidationIssueDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 私人阶段快照到恢复视图的转换，避免未确认来源、私有Key或旧目录冒充已选来源。 */
class SkillImportTaskViewsTest {
    private final SkillGithubSourceMapper sources = mock(SkillGithubSourceMapper.class);
    private final SkillGithubSnapshotCodec codec = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillImportTaskViews views = new SkillImportTaskViews(sources, codec);

    @Test
    void historicalZipNeverQueriesGithubSourceOrChangesExistingResponse() {
        var task = SkillTestFixture.task(); var draft = SkillTestFixture.draft();
        var value = views.view(task, draft);
        assertEquals("ZIP", value.sourceType()); assertEquals("50", value.jobId());
        assertEquals(2L, value.draftRevision()); verifyNoInteractions(sources);
    }

    @Test
    void parsedOptionsAreOnlyExposedDuringConfirmationAndNeverPresentedAsConfirmed() {
        var task = SkillTestFixture.task(); task.setSourceType("GITHUB"); task.setPhase("RESOLVE");
        task.setStatus("WAITING_CONFIRMATION"); task.setWaitUntil(SkillTestFixture.now().plusDays(7));
        task.setSourceKey("PRIVATE_SOURCE_MARKER"); task.setExecutionToken("PRIVATE_TOKEN_MARKER");
        var source = new SkillGithubSourceDO(); source.setJobId(50L);
        var origin = new SkillSourceDTO(1,"GITHUB","https://github.com/a/b","99","feature/a","BRANCH","a".repeat(40),"skills");
        source.setSourceOptionsJson(codec.encode(List.of(new SkillGithubSourceOptionDTO("option", origin,"b".repeat(40))),32768));
        source.setRequestJson(codec.encode(new SkillGithubSourceRequest("a","b","AUTO",null,"","feature/a/skills"),8192));
        when(sources.selectById(50L)).thenReturn(source);
        var value = views.view(task, SkillTestFixture.draft());
        assertEquals(1, value.sourceOptions().size()); assertNull(value.source()); assertNotNull(value.waitUntil());
        assertEquals("https://github.com/a/b/tree/feature/a/skills", value.requestedSource().repositoryUrl());
        assertFalse(value.toString().contains("PRIVATE_"));
        task.setStatus("RUNNING"); assertTrue(views.view(task,SkillTestFixture.draft()).sourceOptions().isEmpty());
    }

    @Test
    void selectedDirectoryAndSafeFailureIssuesRemainBoundToFixedSource() {
        var task = SkillTestFixture.task(); task.setSourceType("GITHUB"); task.setPhase("VALIDATE");
        task.setStatus("FAILED"); task.setErrorCode("SECRET_DETECTED"); task.setNextRetryAt(SkillTestFixture.now().plusMinutes(5));
        var source = new SkillGithubSourceDO(); source.setJobId(50L); source.setSelectedCandidateId("one");
        var origin = new SkillSourceDTO(1,"GITHUB","https://github.com/a/b","99","main","BRANCH","a".repeat(40),"skills");
        source.setConfirmedSourceJson(codec.encode(new SkillGithubSourceOptionDTO("option",origin,"b".repeat(40)),8192));
        source.setCandidatesJson(codec.encode(List.of(new SkillGithubCandidateDTO("one","skills/one","one","说明",1,10L,true,List.of())),8192));
        source.setValidationIssuesJson(codec.encode(List.of(new SkillValidationIssueDTO("SECRET_DETECTED","config.txt",2,"发现敏感信息")),8192));
        source.setScanDigest("c".repeat(64)); when(sources.selectById(50L)).thenReturn(source);
        var value = views.view(task, SkillTestFixture.draft());
        assertEquals("skills/one", value.source().directory()); assertEquals("a".repeat(40),value.source().commitSha());
        assertEquals(1,value.candidateCount()); assertEquals("config.txt",value.issues().getFirst().path());
        assertNotNull(value.retryAt()); assertNull(value.requestedSource());
        source.setSelectedCandidateId("missing"); assertEquals("skills",views.view(task,SkillTestFixture.draft()).source().directory());
        when(sources.selectById(50L)).thenReturn(null);
        value=views.view(task,SkillTestFixture.draft()); assertNull(value.source()); assertNull(value.scanDigest());
        assertEquals(0,value.candidateCount()); assertTrue(value.issues().isEmpty());
    }
}
