package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.mapper.skillhub.SkillDownloadQueryMapper;
import com.yonagi.verse.dao.projection.SkillDownloadProjection;
import com.yonagi.verse.dto.resp.SkillDownloadGrantRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillDownloadRateLimiter;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillDownloadServiceTest {
    private final SkillDownloadQueryMapper query = mock(SkillDownloadQueryMapper.class);
    private final SkillDownloadAuthorizationService authorization = mock(SkillDownloadAuthorizationService.class);
    private final SkillDownloadGrantService grants = mock(SkillDownloadGrantService.class);
    private final SkillDownloadRateLimiter limiter = mock(SkillDownloadRateLimiter.class);
    private final SkillArtifactStore store = mock(SkillArtifactStore.class);
    private final SkillDownloadService service = new SkillDownloadService(query, authorization, grants, limiter, store);
    private final byte[] bytes = new byte[]{1, 2, 3};

    @Test
    void issuesOnlyForAvailableFixedVersionAndPropagatesLimiterFailure() {
        when(query.selectAvailable(20L, 70L)).thenReturn(new SkillDownloadProjection());
        SkillDownloadResource resource = resource();
        when(authorization.authorize(70L)).thenReturn(resource);
        SkillDownloadGrantRespDTO expected = new SkillDownloadGrantRespDTO("/api/v1/skills/downloads/opaque",
                OffsetDateTime.now().plusSeconds(60), resource.sha256(), 3L);
        when(grants.create("subject", resource)).thenReturn(expected);
        assertSame(expected, service.issue(20L, 70L, "subject", "ip"));
        doThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE)).when(limiter).check("failed", "ip");
        assertThrows(ServerException.class, () -> service.issue(20L, 70L, "failed", "ip"));
        assertThrows(ClientException.class, () -> service.issue(20L, 71L, "subject", "ip"));
    }

    @Test
    void authorizesAgainAfterReadingStorageAndTransfersIdenticalBytes() {
        prepare();
        AtomicReference<SkillDownloadPayload> payload = new AtomicReference<>();
        service.download("token", "subject", "ip", payload::set);
        assertArrayEquals(bytes, payload.get().bytes());
        assertEquals("test-1.0.0.zip", payload.get().filename());
        assertEquals(DigestUtil.sha256Hex(bytes), payload.get().sha256());
        verify(authorization, org.mockito.Mockito.times(2)).authorize(70L);
        verify(grants, org.mockito.Mockito.times(2)).resolve("token");
    }

    @Test
    void oldGrantCannotTransferAfterUnpublishDuringStorageRead() {
        prepare();
        when(authorization.authorize(70L)).thenReturn(resource())
                .thenThrow(new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE));
        AtomicInteger writes = new AtomicInteger();
        assertThrows(ClientException.class,
                () -> service.download("token", "subject", "ip", payload -> writes.incrementAndGet()));
        assertEquals(0, writes.get());
    }

    @Test
    void corruptArchiveExpiredGrantAndStorageFailureNeverStartResponse() {
        prepare();
        AtomicInteger writes = new AtomicInteger();
        when(store.get(any(), anyInt())).thenReturn(new byte[]{9, 9, 9});
        assertThrows(ServerException.class,
                () -> service.download("token", "subject", "ip", payload -> writes.incrementAndGet()));
        when(store.get(any(), anyInt())).thenThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE));
        assertThrows(ServerException.class,
                () -> service.download("token", "subject", "ip", payload -> writes.incrementAndGet()));
        when(grants.resolve("expired")).thenThrow(new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE));
        assertThrows(ClientException.class,
                () -> service.download("expired", "subject", "ip", payload -> writes.incrementAndGet()));
        assertEquals(0, writes.get());
    }

    @Test
    void transferFailureReleasesBudgetForSubsequentDownloads() {
        prepare();
        for (int index = 0; index < 3; index++) {
            assertThrows(IllegalStateException.class, () -> service.download("token", "subject", "ip",
                    payload -> { throw new IllegalStateException("client disconnected"); }));
        }
        AtomicInteger writes = new AtomicInteger();
        service.download("token", "subject", "ip", payload -> writes.incrementAndGet());
        assertEquals(1, writes.get());
    }

    @Test
    void rejectsInvalidGrantBeforeAnyStorageRead() {
        when(grants.resolve("invalid")).thenThrow(new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE));
        assertThrows(ClientException.class, () -> service.download("invalid", "subject", "ip", payload -> { }));
        verify(store, never()).get(any(), anyInt());
    }

    private void prepare() {
        when(grants.resolve("token")).thenReturn(70L);
        when(authorization.authorize(70L)).thenReturn(resource());
        when(store.get(any(), anyInt())).thenReturn(bytes);
    }

    private SkillDownloadResource resource() {
        return new SkillDownloadResource(70L, "test-1.0.0.zip", "skillhub/v1/artifact/50/test.zip",
                DigestUtil.sha256Hex(bytes), 3L);
    }
}
