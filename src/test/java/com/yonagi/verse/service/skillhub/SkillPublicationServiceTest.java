package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillSubmissionDO;
import com.yonagi.verse.dao.entity.skillhub.SkillApprovalDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillApprovalMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillSubmissionMapper;
import com.yonagi.verse.dto.req.SkillDraftSubmitReqDTO;
import com.yonagi.verse.dto.resp.SkillPublicationRespDTO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 只验证提交业务状态与依赖失败，不用Mock声称数据库事务回滚已经成立。 */
class SkillPublicationServiceTest {
    private static final String KEY = "3e9199de-72fb-4eb5-8cae-64fe0f2a0a91";
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillPublicationPolicy policy = mock(SkillPublicationPolicy.class);
    private final SkillPublicationSnapshotCodec codec = new SkillPublicationSnapshotCodec(new ObjectMapper());
    private final SkillIdempotencyService commands = mock(SkillIdempotencyService.class);
    private final SkillPublicationWriter writer = mock(SkillPublicationWriter.class);
    private final SkillSubmissionMapper submissions = mock(SkillSubmissionMapper.class);
    private final SkillApprovalMapper approvals = mock(SkillApprovalMapper.class);
    private final SkillPublicationService service = new SkillPublicationService(access, artifacts, policy, codec,
            commands, writer, submissions, approvals);

    @Test
    void replayHappensBeforeConsumedDraftRejectionAndDoesNotRepublish() {
        SkillOwnedDraft owned = ready();
        owned.draft().setStatus("SUBMITTED");
        SkillPublicationRespDTO receipt = receipt();
        when(commands.replay(1L, "SUBMIT_DRAFT", KEY, List.of(30L, 2L), SkillPublicationRespDTO.class))
                .thenReturn(receipt);
        assertSame(receipt, service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        verify(writer, never()).publish(any(), any(), any());
        verify(artifacts, never()).selectByIdForUpdate(any());
    }

    @Test
    void readySubmissionFreezesMetadataAndRecordsOnlySuccessfulReceipt() {
        SkillOwnedDraft owned = ready();
        SkillArtifactDO artifact = SkillTestFixture.artifact();
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(artifact);
        when(writer.publish(eq(owned), eq(artifact), any())).thenReturn(receipt());
        SkillPublicationRespDTO result = service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY);
        assertEquals("70", result.versionId());
        assertEquals("APPROVED", result.approvalStatus());
        verify(policy).validateSubmission(SkillTestFixture.metadata());
        verify(commands).record(1L, "SUBMIT_DRAFT", KEY, List.of(30L, 2L), 60L, result);
    }

    @Test
    void staleRevisionUnreadyDraftAndWrongArtifactOwnerCannotReachPublication() {
        SkillOwnedDraft owned = ready();
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(1L), KEY));
        owned.draft().setStatus("EDITING");
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        owned.draft().setStatus("READY");
        SkillArtifactDO artifact = SkillTestFixture.artifact();
        artifact.setOwnerUserId(2L);
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(artifact);
        ClientException failure = assertThrows(ClientException.class,
                () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        assertEquals(SkillErrorCodeEnum.PACKAGE_INVALID.code(), failure.getErrorCode());
        verify(writer, never()).publish(any(), any(), any());
        verify(commands, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void licenseRejectionAndWriterFailurePropagateWithoutSuccessReceipt() {
        ready();
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(SkillTestFixture.artifact());
        doThrow(new ClientException(SkillErrorCodeEnum.LICENSE_REQUIRED)).when(policy).validateSubmission(any());
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        verify(writer, never()).publish(any(), any(), any());
        org.mockito.Mockito.doNothing().when(policy).validateSubmission(any());
        when(writer.publish(any(), any(), any())).thenThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE));
        assertThrows(ServerException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        verify(commands, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void malformedInputAndAccessFailureDoNotReadArtifactOrWriteReceipt() {
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, null, KEY));
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(-1L), KEY));
        when(access.lockDraft(1L, 30L)).thenThrow(new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE));
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        verify(artifacts, never()).selectByIdForUpdate(any());
        verify(writer, never()).publish(any(), any(), any());
    }

    @Test
    void receiptRestoresApprovalFactAndReflectsCurrentUnpublishState() {
        SkillTestFixture.initializeMapping(SkillSubmissionDO.class, SkillApprovalDO.class);
        SkillOwnedDraft owned = ready();
        when(access.readDraft(1L, 30L)).thenReturn(owned);
        SkillSubmissionDO submission = new SkillSubmissionDO();
        submission.setSkillId(20L);
        submission.setDraftId(30L);
        submission.setVersionId(70L);
        when(submissions.selectOne(any())).thenReturn(submission);
        assertThrows(ServerException.class, () -> service.get(1L, 60L));
        SkillApprovalDO approval = new SkillApprovalDO();
        approval.setApprovalId(80L);
        approval.setStatus("APPROVED");
        approval.setDecisionSource("LOCAL_AUTO");
        when(approvals.selectOne(any())).thenReturn(approval);
        owned.skill().setStatus("ACTIVE");
        assertEquals(receipt(), service.get(1L, 60L));
        owned.skill().setStatus("OWNER_UNPUBLISHED");
        assertEquals("OWNER_UNPUBLISHED", service.get(1L, 60L).publicationStatus());
        when(submissions.selectOne(any())).thenReturn(null);
        assertThrows(ClientException.class, () -> service.get(2L, 60L));
    }

    @Test
    void incompleteDraftAndUnavailableArtifactCannotBeSubmitted() {
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(null), KEY));
        SkillOwnedDraft owned = ready();
        owned.draft().setArtifactId(null);
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        owned.draft().setArtifactId(40L);
        owned.draft().setMetadataJson(null);
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        owned.draft().setMetadataJson(codec.encode(SkillTestFixture.metadata()));
        owned.skill().setStatus("ACTIVE");
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        owned.skill().setStatus("DRAFT");
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        SkillArtifactDO artifact = SkillTestFixture.artifact();
        when(artifacts.selectByIdForUpdate(40L)).thenReturn(artifact);
        artifact.setStatus("DELETE_PENDING");
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        artifact.setStatus("READY");
        artifact.setArchiveBytes(20L * 1024 * 1024 + 1);
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        artifact.setArchiveBytes(3L);
        artifact.setFileCount(501);
        assertThrows(ClientException.class, () -> service.submit(1L, 30L, new SkillDraftSubmitReqDTO(2L), KEY));
        verify(writer, never()).publish(any(), any(), any());
    }

    private SkillOwnedDraft ready() {
        SkillDraftDO draft = SkillTestFixture.draft();
        draft.setStatus("READY");
        draft.setArtifactId(40L);
        draft.setMetadataJson(codec.encode(SkillTestFixture.metadata()));
        SkillOwnedDraft owned = new SkillOwnedDraft(SkillTestFixture.publisher(), SkillTestFixture.skill(), draft);
        when(access.lockDraft(1L, 30L)).thenReturn(owned);
        return owned;
    }

    private SkillPublicationRespDTO receipt() {
        return new SkillPublicationRespDTO("20", "60", "80", "70", "APPROVED", "LOCAL_AUTO", "PUBLISHED",
                "author", "test-skill");
    }
}
