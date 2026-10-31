package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dto.req.SkillImportCreateReqDTO;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillImportTaskServiceTest {
    private static final String KEY = "49a3d831-c7f1-4d70-ae81-c044921bf2a9";
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final SkillImportTaskMapper tasks = mock(SkillImportTaskMapper.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final SkillIdempotencyService commands = mock(SkillIdempotencyService.class);
    private final SkillImportTaskService service = new SkillImportTaskService(access, tasks, drafts, commands);
    private final SkillDraftDO draft = SkillTestFixture.draft();
    private final SkillImportTaskDO task = SkillTestFixture.task();

    @BeforeEach
    void ownership() {
        SkillTestFixture.initializeMapping(SkillDraftDO.class, SkillImportTaskDO.class);
        draft.setLatestJobId(50L);
        SkillOwnedDraft owned = new SkillOwnedDraft(SkillTestFixture.publisher(), SkillTestFixture.skill(), draft);
        when(access.lockDraft(1L, 30L)).thenReturn(owned);
        when(access.readDraft(1L, 30L)).thenReturn(owned);
        when(tasks.selectOne(any())).thenReturn(task);
        when(tasks.selectByIdForUpdate(50L)).thenReturn(task);
    }

    @Test
    void createsRecoverableTaskAndInvalidatesPreviousArtifactPreview() {
        draft.setArtifactId(40L);
        draft.setStatus("READY");
        SkillImportTaskRespDTO result = service.create(1L, 30L, new SkillImportCreateReqDTO(2L), KEY);
        ArgumentCaptor<SkillImportTaskDO> inserted = ArgumentCaptor.forClass(SkillImportTaskDO.class);
        verify(tasks).insert(inserted.capture());
        assertEquals("WAITING_UPLOAD", result.status());
        assertEquals(3L, result.draftRevision());
        assertEquals(inserted.getValue().getJobId().toString(), result.jobId());
        assertEquals(1L, inserted.getValue().getOwnerUserId());
        assertEquals("EDITING", draft.getStatus());
        assertNull(draft.getArtifactId());
    }

    @Test
    void staleRevisionAndQuotaRejectionNeverCreateAnotherTask() {
        assertThrows(ClientException.class, () -> service.create(1L, 30L, new SkillImportCreateReqDTO(1L), KEY));
        when(tasks.selectCount(any())).thenReturn(2L, 0L);
        ClientException rate = assertThrows(ClientException.class,
                () -> service.create(1L, 30L, new SkillImportCreateReqDTO(2L), KEY));
        assertEquals(SkillErrorCodeEnum.RATE_LIMITED.code(), rate.getErrorCode());
        when(tasks.selectCount(any())).thenReturn(0L, 20L);
        assertThrows(ClientException.class, () -> service.create(1L, 30L, new SkillImportCreateReqDTO(2L), KEY));
        verify(tasks, never()).insert(any(SkillImportTaskDO.class));
    }

    @Test
    void reuploadAllocatesDifferentKeyAndOldUploadCannotBindNewInput() {
        SkillUploadClaim old = service.beginUpload(1L, 50L);
        assertEquals("UPLOADING", task.getStatus());
        assertTrue(old.sourceKey().startsWith("skillhub/v1/source/50/"));
        assertThrows(ClientException.class, () -> service.beginUpload(1L, 50L));
        task.setUploadUntil(SkillTestFixture.now().minusSeconds(1));
        SkillUploadClaim current = service.beginUpload(1L, 50L);
        assertNotEquals(old.sourceKey(), current.sourceKey());
        assertNotEquals(old.token(), current.token());
        assertThrows(ClientException.class, () -> service.completeUpload(old, "a".repeat(64), 10L));
        service.completeUpload(current, "b".repeat(64), 20L);
        assertEquals("QUEUED", task.getStatus());
        assertEquals("b".repeat(64), task.getSourceSha256());
        assertEquals(20L, task.getSourceBytes());
    }

    @Test
    void processingWindowStartsAfterUploadAndCannotBeResetByDuplicateCompletion() {
        LocalDateTime created = SkillTestFixture.now().minusHours(5);
        task.setCreateTime(created);
        SkillUploadClaim claim = service.beginUpload(1L, 50L);
        assertNull(task.getProcessingDeadline());
        LocalDateTime beforeComplete = SkillTestFixture.now();
        service.completeUpload(claim, "a".repeat(64), 10L);
        LocalDateTime deadline = task.getProcessingDeadline();
        assertEquals("QUEUED", task.getStatus());
        assertEquals(task.getNextRetryAt().plusMinutes(10), deadline);
        assertTrue(!deadline.isBefore(beforeComplete.plusMinutes(10)));
        assertTrue(!deadline.isAfter(SkillTestFixture.now().plusMinutes(10)));
        assertEquals(created, task.getCreateTime());
        assertThrows(ClientException.class, () -> service.completeUpload(claim, "b".repeat(64), 20L));
        assertEquals(deadline, task.getProcessingDeadline());
        assertEquals(10L, task.getSourceBytes());
        assertEquals("a".repeat(64), task.getSourceSha256());
    }

    @Test
    void cancellationIsTerminalForUnfinishedTaskAndCannotCancelSuccessfulArtifact() {
        task.setStatus("RUNNING");
        service.cancel(1L, 50L);
        assertEquals("CANCELLED", task.getStatus());
        assertThrows(ClientException.class, () -> service.beginUpload(1L, 50L));
        task.setStatus("SUCCEEDED");
        service.cancel(1L, 50L);
        assertEquals("SUCCEEDED", service.get(1L, 50L).status());
    }

    @Test
    void unknownOrNonCurrentTaskAndSubmittedDraftAreRejected() {
        when(tasks.selectOne(any())).thenReturn(null);
        assertThrows(ClientException.class, () -> service.get(1L, 99L));
        verify(tasks, never()).selectByIdForUpdate(anyLong());
        when(tasks.selectOne(any())).thenReturn(task);
        draft.setLatestJobId(51L);
        assertThrows(ClientException.class, () -> service.beginUpload(1L, 50L));
        draft.setLatestJobId(50L);
        draft.setStatus("SUBMITTED");
        assertThrows(ClientException.class, () -> service.beginUpload(1L, 50L));
        assertThrows(ClientException.class, () -> service.create(1L, 30L, new SkillImportCreateReqDTO(2L), KEY));
    }
}
