package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactFileDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactFileMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillCleanupMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证清理决策与状态，不用Mock证明数据库行锁或物理删除正确。 */
class SkillArtifactCleanupServiceTest {
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillArtifactFileMapper files = mock(SkillArtifactFileMapper.class);
    private final SkillImportTaskMapper tasks = mock(SkillImportTaskMapper.class);
    private final SkillCleanupMapper cleanup = mock(SkillCleanupMapper.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final SkillArtifactCleanupService service =
            new SkillArtifactCleanupService(artifacts, files, tasks, cleanup, drafts);

    @BeforeEach
    void mapping() {
        SkillTestFixture.initializeMapping(SkillArtifactDO.class, SkillArtifactFileDO.class,
                SkillImportTaskDO.class, SkillDraftDO.class);
    }

    @Test
    void referencedDeletedOrMissingArtifactNeverYieldsDeletionKey() {
        assertNull(service.claimArtifact(40L));
        SkillArtifactDO artifact = SkillTestFixture.artifact();
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(artifact);
        when(cleanup.selectArtifactReferenced(40L)).thenReturn(true);
        assertNull(service.claimArtifact(40L));
        artifact.setStatus("DELETED");
        assertNull(service.claimArtifact(40L));
        verify(artifacts, never()).update(any(), any());
    }

    @Test
    void deletionKeyRequiresSuccessfulTransitionAndMetadataErasureRequiresAcknowledgement() {
        SkillArtifactDO artifact = SkillTestFixture.artifact();
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(artifact);
        when(artifacts.update(any(), any())).thenReturn(0, 1, 0, 1);
        assertNull(service.claimArtifact(40L));
        assertEquals(artifact.getArchiveKey(), service.claimArtifact(40L));
        service.artifactDeleted(40L, artifact.getArchiveKey());
        verify(files, never()).delete(any());
        service.artifactDeleted(40L, artifact.getArchiveKey());
        verify(files).delete(any());
    }

    @Test
    void activeImportSourceCannotBeDeletedWhileCancelledSourceCan() {
        assertNull(service.sourceKey(50L));
        SkillImportTaskDO task = SkillTestFixture.task();
        task.setSourceKey("private/source.zip");
        task.setStatus("RUNNING");
        when(tasks.selectByIdForUpdate(50L)).thenReturn(task);
        assertNull(service.sourceKey(50L));
        task.setStatus("UPLOADING");
        assertNull(service.sourceKey(50L));
        task.setStatus("CANCELLED");
        assertEquals("private/source.zip", service.sourceKey(50L));
        task.setStatus("WAITING_UPLOAD");
        assertEquals("private/source.zip", service.sourceKey(50L));
    }

    @Test
    void onlyStaleEditableDraftExpiresAndAdvancesRevision() {
        SkillDraftDO draft = SkillTestFixture.draft();
        when(drafts.selectById(30L)).thenReturn(draft);
        when(drafts.selectByIdForUpdate(30L)).thenReturn(draft);
        service.expireDraft(30L);
        assertEquals("EDITING", draft.getStatus());
        draft.setUpdateTime(SkillTestFixture.now().minusDays(31));
        draft.setStatus("SUBMITTED");
        service.expireDraft(30L);
        assertEquals("SUBMITTED", draft.getStatus());
        verify(drafts, never()).updateById(any());
        draft.setStatus("READY");
        service.expireDraft(30L);
        assertEquals("EXPIRED", draft.getStatus());
        assertEquals(3L, draft.getRevision());
        verify(drafts).updateById(draft);
    }

    @Test
    void concurrentlyRemovedDraftDoesNotCreateFalseExpiry() {
        service.expireDraft(30L);
        when(drafts.selectById(30L)).thenReturn(SkillTestFixture.draft());
        service.expireDraft(30L);
        verify(drafts, never()).updateById(any());
        verify(tasks, never()).update(any(), any());
    }
}
