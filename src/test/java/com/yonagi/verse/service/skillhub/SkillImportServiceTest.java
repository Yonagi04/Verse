package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillImportServiceTest {
    private final SkillImportTaskService tasks = mock(SkillImportTaskService.class);
    private final SkillArtifactStore store = mock(SkillArtifactStore.class);
    private final SkillImportService service = new SkillImportService(tasks, store);
    private final SkillUploadClaim claim = new SkillUploadClaim(50L, 30L, 1L, "generation", "private/source.zip");
    private final byte[] bytes = new byte[]{1, 2, 3};

    @Test
    void queuesExactStoredBytesWithoutClaimingValidationSucceeded() {
        when(tasks.beginUpload(1L, 50L)).thenReturn(claim);
        SkillImportTaskRespDTO queued = new SkillImportTaskRespDTO("50", "30", "QUEUED", 3L,
                null, null, 0, OffsetDateTime.now());
        when(tasks.get(1L, 50L)).thenReturn(queued);
        assertSame(queued, service.upload(1L, 50L, bytes));
        verify(store).put(claim.sourceKey(), bytes);
        verify(tasks).completeUpload(claim, DigestUtil.sha256Hex(bytes), bytes.length);
    }

    @Test
    void invalidBodyAndStorageFailureNeverBindArtifactButReleaseUploadGeneration() {
        when(tasks.beginUpload(1L, 50L)).thenReturn(claim);
        assertThrows(ClientException.class, () -> service.upload(1L, 50L, null));
        assertThrows(ClientException.class, () -> service.upload(1L, 50L, new byte[0]));
        assertThrows(ClientException.class, () -> service.upload(1L, 50L, new byte[20 * 1024 * 1024 + 1]));
        doThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE)).when(store).put(any(), any());
        assertThrows(ServerException.class, () -> service.upload(1L, 50L, bytes));
        verify(tasks, never()).completeUpload(any(), any(), anyLong());
        verify(tasks, org.mockito.Mockito.times(4)).uploadFailed(claim);
    }

    @Test
    void rejectionAndFailedRecoveryCannotLeakProcessUploadBudget() {
        when(tasks.beginUpload(1L, 50L)).thenThrow(new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE));
        for (int attempt = 0; attempt < 3; attempt++) {
            ClientException failure = assertThrows(ClientException.class, () -> service.upload(1L, 50L, bytes));
            assertEquals(SkillErrorCodeEnum.NOT_ACCESSIBLE.code(), failure.getErrorCode());
        }
        org.mockito.Mockito.doReturn(claim).when(tasks).beginUpload(1L, 50L);
        doThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE)).when(tasks).uploadFailed(claim);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(ServerException.class, () -> service.upload(1L, 50L, bytes));
        }
        doNothing().when(tasks).uploadFailed(claim);
        service.upload(1L, 50L, bytes);
        verify(tasks, org.mockito.Mockito.times(4)).completeUpload(eq(claim), any(), eq(3L));
    }

    @Test
    void onlyTwoStorageWritesCanBeInFlightAndThirdIsRejectedBeforeReservation() {
        when(tasks.beginUpload(1L, 50L)).thenReturn(claim);
        AtomicInteger depth = new AtomicInteger();
        doAnswer(invocation -> {
            if (depth.incrementAndGet() == 1) {
                service.upload(1L, 50L, bytes);
            } else {
                ClientException failure = assertThrows(ClientException.class,
                        () -> service.upload(1L, 50L, bytes));
                assertEquals(SkillErrorCodeEnum.RATE_LIMITED.code(), failure.getErrorCode());
            }
            depth.decrementAndGet();
            return null;
        }).when(store).put(any(), any());
        service.upload(1L, 50L, bytes);
        assertEquals(0, depth.get());
        verify(tasks, org.mockito.Mockito.times(2)).beginUpload(1L, 50L);
    }
}
