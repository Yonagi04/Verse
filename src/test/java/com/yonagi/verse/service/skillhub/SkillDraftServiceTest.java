package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillMapper;
import com.yonagi.verse.dto.req.SkillDraftCreateReqDTO;
import com.yonagi.verse.dto.req.SkillDraftSaveReqDTO;
import com.yonagi.verse.dto.resp.SkillDraftRespDTO;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillDraftServiceTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final SkillPublisherService publishers = mock(SkillPublisherService.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final SkillMapper skills = mock(SkillMapper.class);
    private final SkillImportTaskMapper tasks = mock(SkillImportTaskMapper.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillIdempotencyService commands = mock(SkillIdempotencyService.class);
    private final SkillPublicationPolicy policy = mock(SkillPublicationPolicy.class);
    private final SkillPublicationSnapshotCodec codec = new SkillPublicationSnapshotCodec(new ObjectMapper());
    private final SkillDraftService service = new SkillDraftService(access, publishers, drafts, skills, tasks,
            artifacts, commands, policy, codec, new ObjectMapper(), VALIDATORS.getValidator());
    private final SkillDraftDO draft = SkillTestFixture.draft();

    @BeforeEach
    void ownership() {
        SkillTestFixture.initializeMapping(SkillDraftDO.class, SkillImportTaskDO.class);
        SkillOwnedDraft owned = new SkillOwnedDraft(SkillTestFixture.publisher(), SkillTestFixture.skill(), draft);
        when(access.lockDraft(1L, 30L)).thenReturn(owned);
        when(access.readDraft(1L, 30L)).thenReturn(owned);
        when(publishers.ensure(1L)).thenReturn(owned.publisher());
    }

    @AfterAll
    static void closeValidation() {
        VALIDATORS.close();
    }

    @Test
    void createsPrivateStableSlugAndExplicitOwnerWithoutPublishingAnything() {
        SkillDraftRespDTO result = service.create(1L, new SkillDraftCreateReqDTO("test-skill"), "key");
        ArgumentCaptor<SkillDO> skill = ArgumentCaptor.forClass(SkillDO.class);
        ArgumentCaptor<SkillDraftDO> created = ArgumentCaptor.forClass(SkillDraftDO.class);
        verify(skills).insert(skill.capture());
        verify(drafts).insert(created.capture());
        verify(publishers).ensure(1L);
        assertEquals(10L, skill.getValue().getPublisherId());
        assertEquals("DRAFT", skill.getValue().getStatus());
        assertNull(skill.getValue().getCurrentVersionId());
        assertEquals(1L, created.getValue().getOwnerUserId());
        assertEquals("EDITING", result.status());
        assertEquals(created.getValue().getDraftId().toString(), result.draftId());
        assertNull(result.skillMarkdown());
    }

    @Test
    void savesExactMetadataOnMatchingRevisionAndCannotOverwriteConsumedDraft() {
        when(drafts.update(any(), any())).thenReturn(1);
        SkillDraftRespDTO result = service.save(1L, 30L, new SkillDraftSaveReqDTO(2L, SkillTestFixture.metadata()));
        assertEquals(3L, result.revision());
        assertEquals(SkillTestFixture.metadata(), result.metadata());
        draft.setStatus("SUBMITTED");
        assertThrows(ClientException.class,
                () -> service.save(1L, 30L, new SkillDraftSaveReqDTO(3L, SkillTestFixture.metadata())));
    }

    @Test
    void staleRevisionAndLostCompareAndSetCannotReportSaved() {
        assertThrows(ClientException.class,
                () -> service.save(1L, 30L, new SkillDraftSaveReqDTO(1L, SkillTestFixture.metadata())));
        assertThrows(ClientException.class,
                () -> service.save(1L, 30L, new SkillDraftSaveReqDTO(2L, SkillTestFixture.metadata())));
        assertEquals(2L, draft.getRevision());
        assertNull(draft.getMetadataJson());
    }

    @Test
    void abandonCancelsUnfinishedTaskAndIsIdempotent() {
        service.abandon(1L, 30L, 2L);
        assertEquals("CANCELLED", draft.getStatus());
        assertEquals(3L, draft.getRevision());
        service.abandon(1L, 30L, 2L);
        assertEquals(3L, draft.getRevision());
        verify(tasks).update(any(), any());
    }

    @Test
    void privatePreviewRequiresReadyArtifactOwnedBySameAccount() {
        draft.setArtifactId(40L);
        SkillArtifactDO artifact = SkillTestFixture.artifact();
        when(artifacts.selectById(40L)).thenReturn(artifact);
        assertEquals("test-skill", service.get(1L, 30L).packageName());
        assertEquals("# 测试", service.get(1L, 30L).skillMarkdown());
        artifact.setOwnerUserId(2L);
        assertNull(service.get(1L, 30L).skillMarkdown());
        artifact.setOwnerUserId(1L);
        artifact.setStatus("DELETE_PENDING");
        assertNull(service.get(1L, 30L).archiveBytes());
        artifact.setStatus("READY");
        artifact.setManifestJson("invalid");
        assertThrows(ServerException.class, () -> service.get(1L, 30L));
    }

    @Test
    void invalidInputNeverCreatesResource() {
        assertThrows(ClientException.class, () -> service.create(1L, new SkillDraftCreateReqDTO("UPPER"), "key"));
        assertThrows(ClientException.class, () -> service.save(1L, 30L, null));
        verify(skills, never()).insert(any(SkillDO.class));
        verify(drafts, never()).insert(any(SkillDraftDO.class));
    }
}
