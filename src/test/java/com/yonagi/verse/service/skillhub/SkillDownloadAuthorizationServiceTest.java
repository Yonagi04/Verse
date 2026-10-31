package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDownloadQueryMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class SkillDownloadAuthorizationServiceTest {
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final SkillDownloadQueryMapper query = mock(SkillDownloadQueryMapper.class);
    private final SkillVersionMapper versions = mock(SkillVersionMapper.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillDownloadAuthorizationService service =
            new SkillDownloadAuthorizationService(access, query, versions, artifacts);
    private final SkillDO skill = SkillTestFixture.skill();
    private final SkillArtifactDO artifact = SkillTestFixture.artifact();
    private final SkillVersionDO version = new SkillVersionDO();

    @BeforeEach
    void available() {
        skill.setStatus("ACTIVE");
        skill.setCurrentVersionId(70L);
        version.setVersionId(70L);
        version.setSkillId(20L);
        version.setArtifactId(40L);
        version.setVersionLabel("1.0.0");
        version.setStatus("AVAILABLE");
        when(query.selectOwnerId(70L)).thenReturn(1L);
        SkillVersionDO reference = new SkillVersionDO();
        reference.setSkillId(20L);
        when(versions.selectById(70L)).thenReturn(reference);
        doReturn(new SkillOwnedResource(SkillTestFixture.publisher(), skill)).when(access).lockSkill(1L, 20L);
        when(versions.selectByIdForUpdate(70L)).thenReturn(version);
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(artifact);
    }

    @Test
    void authorizesOnlyCurrentAvailableVersionAndOwnedReadyArtifact() {
        SkillDownloadResource result = service.authorize(70L);
        assertEquals(70L, result.versionId());
        assertEquals("test-skill-1.0.0.zip", result.filename());
        assertEquals(artifact.getArchiveKey(), result.archiveKey());
        assertEquals(artifact.getArchiveSha256(), result.sha256());
    }

    @Test
    void oldGrantCannotBypassAccountClosureUnpublishOrVersionReplacement() {
        when(access.lockSkill(1L, 20L)).thenThrow(ClientException.class);
        assertThrows(ClientException.class, () -> service.authorize(70L));
        doReturn(new SkillOwnedResource(SkillTestFixture.publisher(), skill)).when(access).lockSkill(1L, 20L);
        skill.setStatus("OWNER_UNPUBLISHED");
        assertThrows(ClientException.class, () -> service.authorize(70L));
        skill.setStatus("ACTIVE");
        skill.setCurrentVersionId(71L);
        assertThrows(ClientException.class, () -> service.authorize(70L));
    }

    @Test
    void rejectsDeletedBlockedOrReassignedVersion() {
        when(versions.selectByIdForUpdate(70L)).thenReturn(null);
        assertThrows(ClientException.class, () -> service.authorize(70L));
        when(versions.selectByIdForUpdate(70L)).thenReturn(version);
        version.setStatus("BLOCKED");
        assertThrows(ClientException.class, () -> service.authorize(70L));
        version.setStatus("AVAILABLE");
        version.setSkillId(21L);
        assertThrows(ClientException.class, () -> service.authorize(70L));
    }

    @Test
    void rejectsMissingForeignOrDeletingArtifactAndMissingIdentity() {
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(null);
        assertThrows(ClientException.class, () -> service.authorize(70L));
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(artifact);
        artifact.setOwnerUserId(2L);
        assertThrows(ClientException.class, () -> service.authorize(70L));
        artifact.setOwnerUserId(1L);
        artifact.setStatus("DELETE_PENDING");
        assertThrows(ClientException.class, () -> service.authorize(70L));
        when(query.selectOwnerId(70L)).thenReturn(null);
        assertThrows(ClientException.class, () -> service.authorize(70L));
        when(query.selectOwnerId(70L)).thenReturn(1L);
        when(versions.selectById(70L)).thenReturn(null);
        assertThrows(ClientException.class, () -> service.authorize(70L));
    }
}
