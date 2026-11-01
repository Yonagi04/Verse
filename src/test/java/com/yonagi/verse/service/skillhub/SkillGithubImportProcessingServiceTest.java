package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.adapter.skillhub.validation.SkillInProcessInspector;
import com.yonagi.verse.dao.entity.skillhub.SkillGithubSourceDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillGithubSourceMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import com.yonagi.verse.service.skillhub.port.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.zip.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用例编排隔离GitHub/S3/DB边界；不据此声称外部协议、锁或存储真实正确。 */
class SkillGithubImportProcessingServiceTest {
    @TempDir Path temporary;
    private final SkillImportExecutionService execution = mock(SkillImportExecutionService.class);
    private final SkillGithubSourcePort github = mock(SkillGithubSourcePort.class);
    private final SkillGithubSourceMapper sources = mock(SkillGithubSourceMapper.class);
    private final SkillImportTaskMapper tasks = mock(SkillImportTaskMapper.class);
    private final SkillGithubSnapshotCodec codec = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillArtifactStore store = mock(SkillArtifactStore.class);
    private final SkillRepositoryInspector snapshot = mock(SkillRepositoryInspector.class);
    private final SkillImportTemporaryFiles files = mock(SkillImportTemporaryFiles.class);
    private final SkillGithubImportProcessingService service = new SkillGithubImportProcessingService(execution, github,
            sources, tasks, codec, store, snapshot, new SkillInProcessInspector(), new SkillValidationReportPolicy(),
            files, mock(ThreadPoolTaskScheduler.class));
    private final SkillGithubSourceDO source = new SkillGithubSourceDO();
    private final SkillImportTaskDO task = SkillTestFixture.task();
    private final SkillGithubSourceOptionDTO origin = new SkillGithubSourceOptionDTO("option",
            new SkillSourceDTO(1,"GITHUB","https://github.com/a/b","99","main","BRANCH","a".repeat(40),""),"b".repeat(40));
    private final SkillGithubCandidateDTO candidate = new SkillGithubCandidateDTO("one","one","one","说明",1,10L,true,List.of());
    private Path archive;
    private Path treeFile;
    private byte[] selected;
    private byte[] repository;
    private byte[] tree;

    @BeforeEach
    void setup() throws Exception {
        source.setJobId(50L); source.setConfirmedSourceJson(codec.encode(origin,8192)); source.setSelectedCandidateId("one");
        source.setCandidatesJson(codec.encode(List.of(candidate),256*1024));
        when(sources.selectById(50L)).thenReturn(source); when(tasks.selectById(50L)).thenReturn(task);
        archive=Files.createTempFile(temporary,"repo-",".zip"); treeFile=Files.createTempFile(temporary,"tree-",".json");
        when(files.create(anyLong())).thenReturn(archive,treeFile);
        selected=zip("SKILL.md","---\nname: one\ndescription: 示例\n---\n# one\n");
        repository=zip("repo-sha/one/SKILL.md","---\nname: one\ndescription: 示例\n---\n# one\n");
        tree="{\"tree\":[],\"truncated\":false}".getBytes(StandardCharsets.UTF_8);
        when(github.tree(any(),any())).thenReturn(tree);
        doAnswer(call->{Files.write(call.getArgument(1,Path.class),repository);return null;}).when(github).download(any(),any(),any());
        when(snapshot.discover(any(),any(),anyString(),anyString(),any())).thenReturn(List.of(candidate));
        when(snapshot.extract(any(),any(),any(),any())).thenReturn(selected);
    }

    @Test
    void resolvePersistsWholeFixedOptionListBeforeHumanConfirmationWithoutDownloading() {
        claim("RESOLVE");
        source.setRequestJson(codec.encode(new SkillGithubSourceRequest("a","b","DEFAULT",null,"",""),8192));
        when(github.resolve(any(),any())).thenReturn(List.of(origin));
        service.process(50L);
        ArgumentCaptor<List<SkillGithubSourceOptionDTO>> options=ArgumentCaptor.forClass(List.class);
        verify(execution).resolved(any(),options.capture());
        assertEquals("a".repeat(40),options.getValue().getFirst().source().commitSha());
        assertEquals("https://github.com/a/b",options.getValue().getFirst().source().repositoryUrl());
        verify(github,never()).download(any(),any(),any()); verify(execution,never()).complete(any(),any(),anyString());
    }

    @Test
    void fetchChecksSelectedInputHashRatherThanRepositoryHashAndPublishesOnlyNormalizedArchive() {
        claim("FETCH"); when(execution.fetched(any(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyList())).thenReturn(true);
        service.process(50L);
        ArgumentCaptor<SkillValidationReport> report=ArgumentCaptor.forClass(SkillValidationReport.class);
        verify(execution).complete(any(),report.capture(),contains("skillhub/v1/artifact/50/"));
        assertTrue(report.getValue().success()); assertEquals("one",report.getValue().packageName());
        assertEquals(DigestUtil.sha256Hex(selected),report.getValue().sourceSha256());
        assertNotEquals(DigestUtil.sha256Hex(repository),report.getValue().sourceSha256());
        assertEquals(DigestUtil.sha256Hex(report.getValue().archive()),report.getValue().archiveSha256());
    }

    @Test
    void multipleCandidatesPauseWithoutCallingFinalPackageInspectionOrBindingArtifact() {
        claim("FETCH");
        var second=new SkillGithubCandidateDTO("two","two","two","第二份",1,10L,true,List.of());
        when(snapshot.discover(any(),any(),anyString(),anyString(),any())).thenReturn(List.of(candidate,second));
        when(execution.fetched(any(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyList())).thenReturn(false);
        service.process(50L);
        ArgumentCaptor<List<SkillGithubCandidateDTO>> candidates=ArgumentCaptor.forClass(List.class);
        verify(execution).fetched(any(),anyString(),eq(DigestUtil.sha256Hex(repository)),eq((long)repository.length),
                anyString(),eq(DigestUtil.sha256Hex(tree)),anyString(),candidates.capture());
        assertEquals(List.of("one","two"),candidates.getValue().stream().map(SkillGithubCandidateDTO::candidateId).toList());
        verify(snapshot,never()).extract(any(),any(),any(),any()); verify(execution,never()).complete(any(),any(),anyString());
    }

    @Test
    void selectedCheckReloadsOnlySavedShaSnapshotAndRejectsIntegrityMismatch() throws Exception {
        claim("VALIDATE"); source.setTreeKey("skillhub/v1/source/50/tree.json"); source.setTreeSha256(DigestUtil.sha256Hex(tree));
        task.setSourceKey("skillhub/v1/source/50/repo.zip"); task.setSourceBytes((long)repository.length);
        task.setSourceSha256("wrong");
        doAnswer(call->{Files.write(call.getArgument(1,Path.class),call.getArgument(0).equals(task.getSourceKey())?repository:tree);return null;})
                .when(store).downloadSource(anyString(),any(),anyLong(),any());
        service.process(50L);
        assertEquals("SOURCE_INTEGRITY_FAILED",failure().code());
        verify(github,never()).download(any(),any(),any()); verify(execution,never()).complete(any(),any(),anyString());
    }

    @Test
    void staticRejectionKeepsSafeFileLocationAndDoesNotCreateFinalArchive() throws Exception {
        claim("FETCH"); when(execution.fetched(any(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyList())).thenReturn(true);
        selected=zip("SKILL.md","---\nname: one\ndescription: 示例\n---\n[missing](not-here.txt)");
        when(snapshot.extract(any(),any(),any(),any())).thenReturn(selected);
        service.process(50L);
        SkillGithubSourceException failure=failure();
        assertEquals("INVALID_RESOURCE_REFERENCE",failure.code()); assertFalse(failure.retryable());
        assertEquals("SKILL.md",failure.issues().getFirst().path()); verify(store,never()).put(anyString(),any());
    }

    @Test
    void classifiedRateLimitAndMissingSourceBecomeRecoverableOrTerminalTaskFailures() {
        claim("RESOLVE"); source.setRequestJson(codec.encode(new SkillGithubSourceRequest("a","b","DEFAULT",null,"",""),8192));
        Instant retry=Instant.now().plusSeconds(3600);
        when(github.resolve(any(),any())).thenThrow(new SkillGithubSourceException("GITHUB_RATE_LIMITED","限流",true,retry));
        service.process(50L); assertEquals(retry,failure().retryAt());
        reset(execution); claim("RESOLVE"); when(sources.selectById(50L)).thenReturn(null);
        service.process(50L); assertEquals("INVALID_SOURCE",failure().code()); assertFalse(failure().retryable());
    }

    private void claim(String phase) { when(execution.claim(50L)).thenReturn(new SkillExecutionClaim(50L,30L,1L,"token",null,null,0L,"GITHUB",phase,Instant.now().plusSeconds(120))); }
    private SkillGithubSourceException failure() { ArgumentCaptor<SkillGithubSourceException> value=ArgumentCaptor.forClass(SkillGithubSourceException.class);
        verify(execution).githubFailed(any(),value.capture()); return value.getValue(); }
    private byte[] zip(String path,String text) throws Exception { ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry(path));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}return bytes.toByteArray(); }
}
