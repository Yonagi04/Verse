package com.yonagi.verse.service.skillhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.req.SkillWorkspaceOpenReqDTO;
import com.yonagi.verse.dto.req.SkillWorkspaceSaveReqDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

class SkillVersionWorkspaceServiceTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final SkillVersionMapper versions = mock(SkillVersionMapper.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillCreationSourcePolicy sources = mock(SkillCreationSourcePolicy.class);
    private final SkillPublicationSnapshotCodec snapshots =
            new SkillPublicationSnapshotCodec(new ObjectMapper());
    private final SkillGithubSnapshotCodec json = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillIdempotencyService commands = mock(SkillIdempotencyService.class);
    private final SkillVersionPublicationPolicy publication =
            mock(SkillVersionPublicationPolicy.class);
    private final SkillVersionWorkspaceService service =
            new SkillVersionWorkspaceService(
                    access,
                    drafts,
                    versions,
                    artifacts,
                    sources,
                    snapshots,
                    json,
                    commands,
                    publication,
                    VALIDATORS.getValidator());
    private SkillOwnedDraft owned;
    private final AtomicReference<LambdaUpdateWrapper<SkillDraftDO>> mutation =
            new AtomicReference<>();

    @AfterAll
    static void close() {
        VALIDATORS.close();
    }

    @BeforeEach
    void setup() {
        SkillTestFixture.initializeMapping(SkillDraftDO.class, SkillVersionDO.class);
        owned =
                new SkillOwnedDraft(
                        SkillTestFixture.publisher(),
                        SkillTestFixture.skill(),
                        SkillTestFixture.draft());
        owned.skill().setStatus("ACTIVE");
        owned.skill().setCurrentVersionId(70L);
        owned.skill().setVersionSequence(2L);
        SkillOwnedResource resource = new SkillOwnedResource(owned.publisher(), owned.skill());
        when(access.readSkill(1L, 20L)).thenReturn(resource);
        when(access.lockSkill(1L, 20L)).thenReturn(resource);
        when(access.lockDraft(1L, 30L)).thenReturn(owned);
        when(drafts.selectOne(any())).thenAnswer(call -> owned.draft());
        when(drafts.selectByIdForUpdate(30L)).thenReturn(owned.draft());
        when(drafts.update(isNull(), any()))
                .thenAnswer(
                        call -> {
                            mutation.set(call.getArgument(1));
                            return 1;
                        });
        SkillVersionDO release = new SkillVersionDO();
        release.setVersionId(70L);
        release.setReleaseNumber(1);
        release.setDraftNumber(0);
        release.setDeclarationsJson(snapshots.encode(SkillTestFixture.metadata()));
        when(versions.selectById(70L)).thenReturn(release);
        when(sources.source(owned.skill())).thenReturn(SkillSourceDTO.zip());
    }

    @Test
    void readDoesNotWriteOrRefreshThePreparationBaseline() {
        owned.draft().setBaseSkillRevision(4L);
        var response = service.get(1L, 20L);
        assertEquals("4", response.baseSkillRevision());
        assertEquals("0", response.skillRevision());
        verify(drafts, never()).update(isNull(), any());
    }

    @Test
    void activeWorkspaceRequiresExplicitAbandonmentAndStaleRevisionCannotReopen() {
        assertThrows(
                ClientException.class,
                () ->
                        service.open(
                                1L, 20L, new SkillWorkspaceOpenReqDTO("NEW_CONTENT", 0L), "key"));
        owned.draft().setStatus("SUBMITTED");
        assertThrows(
                ClientException.class,
                () ->
                        service.open(
                                1L, 20L, new SkillWorkspaceOpenReqDTO("NEW_CONTENT", 1L), "key"));
        verify(drafts, never()).update(isNull(), any());
    }

    @Test
    void explicitReopenClearsOldTaskAndArtifactAndAdvancesRevision() {
        owned.draft().setStatus("SUBMITTED");
        service.open(1L, 20L, new SkillWorkspaceOpenReqDTO("NEW_CONTENT", 0L), "key");
        String sql = mutation.get().getSqlSet();
        assertTrue(sql.contains("latest_job_id="));
        assertTrue(sql.contains("artifact_id="));
        assertTrue(sql.contains("source_version_id="));
        assertTrue(sql.contains("base_skill_revision="));
        assertTrue(mutation.get().getParamNameValuePairs().containsValue(3L));
    }

    @Test
    void onlyLatestAvailableDraftCanInitializePromotion() {
        owned.draft().setStatus("SUBMITTED");
        SkillVersionDO head = new SkillVersionDO();
        head.setReleaseNumber(2); head.setDraftNumber(1); head.setSequenceNumber(2L);
        head.setVersionId(71L);
        head.setVersionType("DRAFT");
        head.setStatus("BLOCKED");
        head.setArtifactId(40L);
        when(versions.selectOne(any())).thenReturn(head);
        assertThrows(
                ClientException.class,
                () ->
                        service.open(
                                1L, 20L, new SkillWorkspaceOpenReqDTO("LATEST_DRAFT", 0L), "key"));
        head.setStatus("AVAILABLE");
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(SkillTestFixture.artifact());
        service.open(1L, 20L, new SkillWorkspaceOpenReqDTO("LATEST_DRAFT", 0L), "key");
        assertTrue(mutation.get().getSqlSet().contains("source_version_id="));
        assertTrue(mutation.get().getParamNameValuePairs().containsValue(71L));
    }

    @Test
    void notesSaveHasNoMetadataOrPublicStateMutationAndRejectsStaleOrEmptyNotes() {
        service.save(1L, 20L, new SkillWorkspaceSaveReqDTO(2L, "本次说明"));
        assertTrue(mutation.get().getSqlSet().contains("release_notes="));
        assertFalse(mutation.get().getSqlSet().contains("metadata_json="));
        assertThrows(
                ClientException.class,
                () -> service.save(1L, 20L, new SkillWorkspaceSaveReqDTO(1L, "说明")));
        assertThrows(
                ClientException.class,
                () -> service.save(1L, 20L, new SkillWorkspaceSaveReqDTO(2L, " ")));
    }

    @Test
    void platformDisabledHistoryAndDownlistedWritingAreRejected() {
        owned.skill().setStatus("PLATFORM_DISABLED");
        assertThrows(ClientException.class, () -> service.get(1L, 20L));
        owned.skill().setStatus("OWNER_UNPUBLISHED");
        assertEquals("OWNER_UNPUBLISHED", service.get(1L, 20L).skillStatus());
        assertThrows(
                ClientException.class,
                () -> service.save(1L, 20L, new SkillWorkspaceSaveReqDTO(2L, "说明")));
    }
}
