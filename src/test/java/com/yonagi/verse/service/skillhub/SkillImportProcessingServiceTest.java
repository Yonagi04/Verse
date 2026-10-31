package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillPackageInspector;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 导入应用流程的依赖失败与可见副作用，不验证实际线程调度、Redis或S3基础设施。 */
class SkillImportProcessingServiceTest {
    private final SkillImportExecutionService executions = mock(SkillImportExecutionService.class);
    private final SkillArtifactStore store = mock(SkillArtifactStore.class);
    private final SkillPackageInspector inspector = mock(SkillPackageInspector.class);
    private final ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
    private final ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    private final AtomicReference<Runnable> renew = new AtomicReference<>();
    private final SkillImportProcessingService service = new SkillImportProcessingService(executions, store, inspector,
            new SkillValidationReportPolicy(), scheduler);
    private final byte[] source = new byte[]{1, 2, 3};
    private final SkillExecutionClaim claim = new SkillExecutionClaim(50L, 30L, 1L, "execution",
            "skillhub/v1/source/50/input.zip", DigestUtil.sha256Hex(source), 3L);

    @BeforeEach
    void dependencies() {
        when(executions.claim(50L)).thenReturn(claim);
        when(store.get(any(), anyInt())).thenReturn(source);
        when(inspector.inspect(any(), any())).thenReturn(report());
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class))).thenAnswer(invocation -> {
            renew.set(invocation.getArgument(0));
            return heartbeat;
        });
    }

    @Test
    void validSourceAndReportWriteImmutableArtifactBeforeBindingCurrentClaim() {
        service.process(50L);
        String key = "skillhub/v1/artifact/50/execution.zip";
        org.mockito.InOrder sequence = org.mockito.Mockito.inOrder(store, inspector, executions);
        sequence.verify(store).get(eq(claim.sourceKey()), anyInt());
        sequence.verify(inspector).inspect(any(), any());
        sequence.verify(store).put(eq(key), any());
        sequence.verify(executions).complete(eq(claim), any(), eq(key));
        verify(heartbeat).cancel(false);
        verify(executions, never()).fail(any(), any(Boolean.class), any(), any());
    }

    @Test
    void contentRejectionIsTerminalAndDoesNotEchoRawParserReason() {
        when(inspector.inspect(any(), any())).thenReturn(new SkillValidationReport(DigestUtil.sha256Hex(source),
                false, "SECRET_DETECTED", "raw-secret-must-not-escape", null, null, null,
                null, null, null, 0L, List.of()));
        service.process(50L);
        verify(executions).fail(claim, true, "SECRET_DETECTED", "包内疑似包含秘密或本机凭据，请清理后重新上传");
        verify(store, never()).put(any(), any());
        verify(executions, never()).complete(any(), any(), any());
    }

    @Test
    void corruptedSourceCannotReachParserOrReadyBinding() {
        when(store.get(any(), anyInt())).thenReturn(new byte[]{9, 9, 9});
        service.process(50L);
        verify(inspector, never()).inspect(any(), any());
        verify(executions).fail(claim, false, "CHECK_UNAVAILABLE", "检查依赖暂时不可用，正在有界重试");
        verify(executions, never()).complete(any(), any(), any());
    }

    @Test
    void leaseLossDuringInspectionStopsArtifactWriteAndCancelsHeartbeat() {
        when(executions.renew(claim)).thenReturn(false);
        when(inspector.inspect(any(), any())).thenAnswer(invocation -> {
            renew.get().run();
            assertFalse(((java.util.function.BooleanSupplier) invocation.getArgument(1)).getAsBoolean());
            return report();
        });
        service.process(50L);
        verify(store, never()).put(any(), any());
        verify(executions, never()).complete(any(), any(), any());
        verify(heartbeat).cancel(false);
    }

    @Test
    void parserOrPersistenceFailureNeverReportsReadyAndCanRecoverByLease() {
        when(inspector.inspect(any(), any())).thenThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE));
        doAnswer(invocation -> { throw new IllegalStateException("database down"); })
                .when(executions).fail(eq(claim), eq(false), any(), any());
        service.process(50L);
        verify(executions).fail(claim, false, "CHECK_UNAVAILABLE", "检查依赖暂时不可用，正在有界重试");
        verify(heartbeat).cancel(false);
        verify(executions, never()).complete(any(), any(), any());
    }

    @Test
    void resultForDifferentSourceCannotBeBoundOrUploaded() {
        SkillValidationReport valid = report();
        when(inspector.inspect(any(), any())).thenReturn(new SkillValidationReport("f".repeat(64), true,
                null, null, valid.packageName(), valid.packageDescription(), valid.skillMarkdown(), valid.archive(),
                valid.archiveSha256(), valid.contentSha256(), valid.unpackedBytes(), valid.files()));
        service.process(50L);
        verify(store, never()).put(any(), any());
        verify(executions, never()).complete(any(), any(), any());
        verify(executions).fail(claim, false, "CHECK_UNAVAILABLE", "检查依赖暂时不可用，正在有界重试");
    }

    @Test
    void interruptionAfterParsingCannotBeBoundAndRemainsAnExecutionFailure() {
        when(inspector.inspect(any(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return report();
        });
        try {
            service.process(50L);
            assertTrue(Thread.currentThread().isInterrupted());
            verify(store, never()).put(any(), any());
            verify(executions, never()).complete(any(), any(), any());
            verify(executions).fail(claim, false, "CHECK_UNAVAILABLE", "检查依赖暂时不可用，正在有界重试");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unclaimedCandidateDoesNotReadPrivateStorage() {
        when(executions.claim(50L)).thenReturn(null);
        service.process(50L);
        verify(store, never()).get(any(), anyInt());
        verify(inspector, never()).inspect(any(), any());
    }

    @Test
    void confirmedHeartbeatKeepsInspectionAuthorizedButUncertainHeartbeatStopsIt() {
        when(executions.renew(claim)).thenReturn(true);
        when(inspector.inspect(any(), any())).thenAnswer(invocation -> {
            renew.get().run();
            assertTrue(((java.util.function.BooleanSupplier) invocation.getArgument(1)).getAsBoolean());
            return report();
        });
        service.process(50L);
        verify(executions).complete(eq(claim), any(), any());
    }

    @Test
    void failedHeartbeatCannotBeRestoredByLaterTimerAndPreventsParsing() {
        when(executions.renew(claim)).thenThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE));
        when(store.get(any(), anyInt())).thenAnswer(invocation -> {
            renew.get().run();
            renew.get().run();
            return source;
        });
        service.process(50L);
        verify(executions).renew(claim);
        verify(inspector, never()).inspect(any(), any());
        verify(store, never()).put(any(), any());
        verify(executions).fail(claim, false, "CHECK_UNAVAILABLE", "检查依赖暂时不可用，正在有界重试");
    }

    private SkillValidationReport report() {
        byte[] markdown = "# 测试".getBytes(StandardCharsets.UTF_8);
        return new SkillValidationReport(DigestUtil.sha256Hex(source), true, null, null,
                "test-skill", "说明", "# 测试", source, DigestUtil.sha256Hex(source), "a".repeat(64), markdown.length,
                List.of(new SkillValidatedFile("SKILL.md", DigestUtil.sha256Hex(markdown), markdown.length)));
    }
}
