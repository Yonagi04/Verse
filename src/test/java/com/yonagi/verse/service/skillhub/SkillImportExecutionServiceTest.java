package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactFileDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactFileMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillImportExecutionServiceTest {
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final SkillImportTaskMapper tasks = mock(SkillImportTaskMapper.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillArtifactFileMapper files = mock(SkillArtifactFileMapper.class);
    private final SkillImportExecutionService service = new SkillImportExecutionService(access, accounts, tasks,
            drafts, artifacts, files, new ObjectMapper());
    private final SkillDraftDO draft = SkillTestFixture.draft();
    private final SkillImportTaskDO task = SkillTestFixture.task();

    @BeforeEach
    void queued() {
        SkillTestFixture.initializeMapping(SkillDraftDO.class, SkillImportTaskDO.class);
        draft.setLatestJobId(50L);
        task.setStatus("QUEUED");
        task.setSourceKey("skillhub/v1/source/50/source.zip");
        task.setSourceSha256("a".repeat(64));
        task.setSourceBytes(3L);
        when(tasks.selectById(50L)).thenReturn(task);
        when(tasks.selectByIdForUpdate(50L)).thenReturn(task);
        when(drafts.selectById(30L)).thenReturn(draft);
        when(access.lockDraft(1L, 30L)).thenReturn(new SkillOwnedDraft(SkillTestFixture.publisher(),
                SkillTestFixture.skill(), draft));
        when(accounts.isAccountActive(1L)).thenReturn(true);
    }

    @Test
    void oldTaskThatOnlyJustFinishedUploadingCanStartInspection() {
        task.setCreateTime(SkillTestFixture.now().minusHours(5));
        task.setNextRetryAt(SkillTestFixture.now().minusSeconds(1));
        SkillExecutionClaim claim = service.claim(50L);
        assertNotNull(claim);
        assertEquals("RUNNING", task.getStatus());
        assertEquals(1, task.getAttemptCount());
    }

    @Test
    void claimsOnceAndExpiredLeaseGetsNewTokenThatRejectsOldResult() {
        SkillExecutionClaim original = service.claim(50L);
        assertEquals("RUNNING", task.getStatus());
        assertEquals(1, task.getAttemptCount());
        assertNull(service.claim(50L));
        task.setLeaseUntil(SkillTestFixture.now().minusSeconds(1));
        SkillExecutionClaim replacement = service.claim(50L);
        assertNotEquals(original.token(), replacement.token());
        assertEquals(2, task.getAttemptCount());
        assertThrows(ClientException.class, () -> service.complete(original, report(), "skillhub/v1/artifact/old.zip"));
        verify(artifacts, never()).insert(any(SkillArtifactDO.class));
    }

    @Test
    void successfulCurrentTokenCreatesImmutableSnapshotAndReadiesSameDraft() {
        SkillExecutionClaim claim = service.claim(50L);
        service.complete(claim, report(), "skillhub/v1/artifact/50/output.zip");
        ArgumentCaptor<SkillArtifactDO> artifact = ArgumentCaptor.forClass(SkillArtifactDO.class);
        ArgumentCaptor<SkillArtifactFileDO> file = ArgumentCaptor.forClass(SkillArtifactFileDO.class);
        verify(artifacts).insert(artifact.capture());
        verify(files).insert(file.capture());
        assertEquals("READY", draft.getStatus());
        assertEquals(3L, draft.getRevision());
        assertEquals(artifact.getValue().getArtifactId(), draft.getArtifactId());
        assertEquals("SUCCEEDED", task.getStatus());
        assertEquals("# 测试", artifact.getValue().getSkillMarkdown());
        assertEquals("SKILL.md", file.getValue().getRelativePath());
        assertEquals(artifact.getValue().getArtifactId(), file.getValue().getArtifactId());
    }

    @Test
    void cancelledOrSupersededDraftCannotRenewOrPublishLateArtifact() {
        SkillExecutionClaim claim = service.claim(50L);
        draft.setStatus("CANCELLED");
        assertFalse(service.renew(claim));
        assertThrows(ClientException.class, () -> service.complete(claim, report(), "skillhub/v1/artifact/late.zip"));
        draft.setStatus("EDITING");
        draft.setLatestJobId(51L);
        assertFalse(service.renew(claim));
        verify(artifacts, never()).insert(any(SkillArtifactDO.class));
    }

    @Test
    void dependencyFailuresRetryButContentRejectionAndExhaustionAreTerminal() {
        SkillExecutionClaim claim = service.claim(50L);
        service.fail(claim, false, "CHECK_UNAVAILABLE", "稍后重试");
        assertEquals("RETRY_WAIT", task.getStatus());
        task.setNextRetryAt(SkillTestFixture.now().minusSeconds(1));
        claim = service.claim(50L);
        service.fail(claim, true, "SECRET_DETECTED", "包含秘密");
        assertEquals("FAILED", task.getStatus());
        assertEquals("SECRET_DETECTED", task.getErrorCode());
        task.setStatus("QUEUED");
        task.setAttemptCount(3);
        task.setNextRetryAt(SkillTestFixture.now().minusSeconds(1));
        assertNull(service.claim(50L));
        assertEquals("FAILED", task.getStatus());
    }

    @Test
    void disabledOwnerAndCorruptSourceCannotEnterReady() {
        when(accounts.isAccountActive(1L)).thenReturn(false);
        assertNull(service.claim(50L));
        verify(artifacts, never()).insert(any(SkillArtifactDO.class));
        when(accounts.isAccountActive(1L)).thenReturn(true);
        task.setSourceBytes(null);
        assertNull(service.claim(50L));
        assertEquals("FAILED", task.getStatus());
        assertEquals("INVALID_SOURCE", task.getErrorCode());
    }

    @Test
    void explicitUploadDeadlineOverridesOldCreationAndSurvivesRetry() {
        LocalDateTime created = SkillTestFixture.now().minusHours(5);
        LocalDateTime deadline = SkillTestFixture.now().plusMinutes(8);
        task.setCreateTime(created);
        task.setProcessingDeadline(deadline);
        SkillExecutionClaim original = service.claim(50L);
        assertNotNull(original);
        service.fail(original, false, "CHECK_UNAVAILABLE", "正在重试");
        assertEquals("RETRY_WAIT", task.getStatus());
        assertEquals(deadline, task.getProcessingDeadline());
        task.setNextRetryAt(SkillTestFixture.now().minusSeconds(1));
        SkillExecutionClaim replacement = service.claim(50L);
        assertNotNull(replacement);
        assertNotEquals(original.token(), replacement.token());
        assertEquals(deadline, task.getProcessingDeadline());
        assertEquals(created, task.getCreateTime());
    }

    @Test
    void legacyQueuedDeadlineIsFixedBeforeNextRetryTimeChanges() {
        LocalDateTime queuedAt = SkillTestFixture.now().minusMinutes(2);
        task.setCreateTime(SkillTestFixture.now().minusHours(5));
        task.setNextRetryAt(queuedAt);
        SkillExecutionClaim claim = service.claim(50L);
        assertEquals(queuedAt.plusMinutes(10), task.getProcessingDeadline());
        service.fail(claim, false, "CHECK_UNAVAILABLE", "正在重试");
        assertEquals(queuedAt.plusMinutes(10), task.getProcessingDeadline());
        assertTrue(task.getNextRetryAt().isAfter(queuedAt));
    }

    @Test
    void expiredQueueFailsWithoutConsumingAnAttempt() {
        task.setProcessingDeadline(SkillTestFixture.now().minusNanos(1));
        assertNull(service.claim(50L));
        assertEquals("FAILED", task.getStatus());
        assertEquals("CHECK_TIMEOUT", task.getErrorCode());
        assertEquals(0, task.getAttemptCount());
        assertFalse(task.getPublicReason().contains("多次"));
        assertFalse(task.getPublicReason().contains("正在"));
    }

    @Test
    void leaseNeverExtendsBeyondRemainingProcessingWindow() {
        LocalDateTime deadline = SkillTestFixture.now().plusSeconds(15);
        task.setProcessingDeadline(deadline);
        SkillExecutionClaim claim = service.claim(50L);
        assertEquals(deadline, task.getLeaseUntil());
        when(tasks.update(any(), any())).thenReturn(1);
        assertTrue(service.renew(claim));
        assertEquals(deadline, task.getProcessingDeadline());
        assertEquals(1, task.getAttemptCount());
    }

    @Test
    void expiredProcessingWindowRejectsHeartbeatAndCompletionDespiteLiveLease() {
        SkillExecutionClaim claim = service.claim(50L);
        task.setProcessingDeadline(SkillTestFixture.now().minusSeconds(1));
        task.setLeaseUntil(SkillTestFixture.now().plusSeconds(30));
        assertFalse(service.renew(claim));
        assertThrows(ClientException.class, () -> service.complete(claim, report(), "skillhub/v1/artifact/late.zip"));
        verify(artifacts, never()).insert(any(SkillArtifactDO.class));
        assertNull(draft.getArtifactId());
        service.fail(claim, false, "CHECK_UNAVAILABLE", "正在重试");
        assertEquals("FAILED", task.getStatus());
        assertEquals("CHECK_TIMEOUT", task.getErrorCode());
        assertFalse(task.getPublicReason().contains("正在"));
    }

    @Test
    void finalDependencyAttemptReportsExhaustionInsteadOfPromisingAnotherRetry() {
        task.setAttemptCount(2);
        SkillExecutionClaim claim = service.claim(50L);
        service.fail(claim, false, "CHECK_UNAVAILABLE", "正在重试");
        assertEquals("FAILED", task.getStatus());
        assertEquals("CHECK_RETRY_EXHAUSTED", task.getErrorCode());
        assertTrue(task.getPublicReason().contains("已停止重试"));
        assertNull(service.claim(50L));
        assertEquals(3, task.getAttemptCount());
    }

    @Test
    void contentRejectionKeepsItsReasonOnFinalAttempt() {
        task.setAttemptCount(2);
        SkillExecutionClaim claim = service.claim(50L);
        service.fail(claim, true, "SECRET_DETECTED", "包含秘密");
        assertEquals("FAILED", task.getStatus());
        assertEquals("SECRET_DETECTED", task.getErrorCode());
        assertEquals("包含秘密", task.getPublicReason());
    }

    @Test
    void legacyAlreadyRunningTaskUsesOriginalFixedDeadline() {
        LocalDateTime created = SkillTestFixture.now().minusMinutes(11);
        task.setCreateTime(created);
        task.setStatus("RUNNING");
        task.setAttemptCount(1);
        task.setExecutionToken("old");
        task.setLeaseUntil(SkillTestFixture.now().minusSeconds(1));
        assertNull(service.claim(50L));
        assertEquals("CHECK_TIMEOUT", task.getErrorCode());
        assertEquals(created.plusMinutes(10), task.getProcessingDeadline());
        assertEquals(1, task.getAttemptCount());
    }

    @Test
    void historicalTerminalTaskIsNotRevivedOrGivenANewDeadline() {
        task.setStatus("FAILED");
        task.setCreateTime(SkillTestFixture.now().minusHours(5));
        task.setErrorCode("CHECK_UNAVAILABLE");
        assertNull(service.claim(50L));
        assertEquals("FAILED", task.getStatus());
        assertEquals("CHECK_UNAVAILABLE", task.getErrorCode());
        assertNull(task.getProcessingDeadline());
        verify(tasks, never()).updateById(any(SkillImportTaskDO.class));
    }

    private SkillValidationReport report() {
        return new SkillValidationReport("a".repeat(64), true, null, null, "test-skill", "说明", "# 测试",
                new byte[]{1, 2, 3}, "b".repeat(64), "c".repeat(64), 10L,
                List.of(new SkillValidatedFile("SKILL.md", "d".repeat(64), 10L)));
    }
}
