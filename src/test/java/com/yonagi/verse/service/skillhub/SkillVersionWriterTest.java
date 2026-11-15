package com.yonagi.verse.service.skillhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.dao.entity.skillhub.SkillApprovalDO;
import com.yonagi.verse.dao.entity.skillhub.SkillAuditRecordDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDisplayRevisionDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillSubmissionDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillAuditRecordMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDisplayRevisionMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillSubmissionMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.resp.SkillFileChangeRespDTO;
import com.yonagi.verse.dto.resp.SkillFileRespDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 用例写集合的单元验证；数据库原子性另做真实事务演练。 */
class SkillVersionWriterTest {
    private final SkillDisplayRevisionMapper displays = mock(SkillDisplayRevisionMapper.class);
    private final SkillSubmissionMapper submissions = mock(SkillSubmissionMapper.class);
    private final SkillApprovalService approvals = mock(SkillApprovalService.class);
    private final SkillVersionMapper versions = mock(SkillVersionMapper.class);
    private final SkillMapper skills = mock(SkillMapper.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final SkillAuditRecordMapper audits = mock(SkillAuditRecordMapper.class);
    private final SkillPublicationSnapshotCodec codec =
            new SkillPublicationSnapshotCodec(new ObjectMapper());
    private final SkillVersionWriter writer =
            new SkillVersionWriter(
                    displays, submissions, approvals, versions, skills, drafts, audits, codec);
    private final AtomicReference<SkillVersionDO> written = new AtomicReference<>();
    private final AtomicReference<LambdaUpdateWrapper<SkillDO>> update = new AtomicReference<>();
    private SkillOwnedDraft owned;

    @BeforeEach
    void setup() {
        SkillTestFixture.initializeMapping(SkillDO.class, SkillDraftDO.class);
        owned =
                new SkillOwnedDraft(
                        SkillTestFixture.publisher(),
                        SkillTestFixture.skill(),
                        SkillTestFixture.draft());
        when(versions.insert(any(SkillVersionDO.class)))
                .thenAnswer(
                        call -> {
                            written.set(call.getArgument(0));
                            return 1;
                        });
        when(skills.update(isNull(), any()))
                .thenAnswer(
                        call -> {
                            update.set(call.getArgument(1));
                            return 1;
                        });
        when(drafts.update(isNull(), any())).thenReturn(1);
        when(displays.insert(any(SkillDisplayRevisionDO.class))).thenReturn(1);
        when(submissions.insert(any(SkillSubmissionDO.class))).thenReturn(1);
        when(submissions.updateById(any())).thenReturn(1);
        when(audits.insert(any(SkillAuditRecordDO.class))).thenReturn(1);
        when(approvals.createAndApprove(any(), any()))
                .thenAnswer(
                        call -> {
                            SkillApprovalDO approval = new SkillApprovalDO();
                            approval.setApprovalId(99L);
                            approval.setSubmissionId(call.getArgument(0));
                            approval.setRequestSnapshotHash(call.getArgument(1));
                            approval.setStatus("APPROVED");
                            return approval;
                        });
    }

    @Test
    void initialReleaseCreatesOneNodeAndStableCreationAnchor() {
        var receipt = writer.publish(owned, plan("RELEASE", null, null), "key", "hash");
        assertEquals("v1", receipt.version());
        assertNotNull(receipt.submissionId());
        assertEquals(0, receipt.publishedAt().getNano() % 1_000_000, "长期回执必须与MySQL毫秒事实完全一致");
        assertEquals("RELEASE", written.get().getVersionType());
        assertEquals(0, written.get().getDraftNumber());
        assertTrue(update.get().getSqlSet().contains("creation_version_id="));
        assertTrue(update.get().getSqlSet().contains("current_version_id="));
        verify(versions, times(1)).insert(any(SkillVersionDO.class));
    }

    @Test
    void privateDraftCreatesNoApprovalAndDoesNotChangeAnyPublicPointerOrFormalTimestamp() {
        owned.skill().setCurrentVersionId(70L);
        owned.skill().setPublicRevisionId(80L);
        var receipt =
                writer.publish(owned, plan("DRAFT", new SkillVersionDO(), null), "key", "hash");
        assertEquals("DRAFT", receipt.type());
        assertNull(receipt.submissionId());
        assertNull(receipt.approvalId());
        assertNull(written.get().getApprovedSubmissionId());
        assertEquals(80L, written.get().getDisplayRevisionId());
        assertEquals("private notes", written.get().getReleaseNotes());
        assertFalse(update.get().getSqlSet().contains("current_version_id="));
        assertFalse(update.get().getSqlSet().contains("latest_release_at="));
        assertFalse(update.get().getSqlSet().contains("public_revision_id="));
        verifyNoInteractions(approvals, submissions, displays);
    }

    @Test
    void promotionAppendsFormalNodeWithSourceAndDoesNotEditOriginalDraft() {
        owned.skill().setCurrentVersionId(70L);
        owned.skill().setPublicRevisionId(80L);
        SkillVersionDO source = new SkillVersionDO();
        source.setVersionId(71L);
        source.setReleaseNotes("original notes");
        var receipt =
                writer.publish(owned, plan("RELEASE", new SkillVersionDO(), source), "key", "hash");
        assertEquals("71", receipt.sourceDraftVersionId());
        assertEquals("original notes", source.getReleaseNotes());
        assertEquals(80L, written.get().getDisplayRevisionId());
        assertFalse(update.get().getSqlSet().contains("creation_version_id="));
        verifyNoInteractions(displays);
    }

    @Test
    void affectedRowFailureOrInvalidApprovalCannotProduceSuccessfulReceipt() {
        when(drafts.update(isNull(), any())).thenReturn(0);
        assertThrows(
                ClientException.class,
                () -> writer.publish(owned, plan("RELEASE", null, null), "key", "hash"));
        when(drafts.update(isNull(), any())).thenReturn(1);
        when(approvals.createAndApprove(any(), any())).thenReturn(new SkillApprovalDO());
        assertThrows(
                ServerException.class,
                () -> writer.publish(owned, plan("RELEASE", null, null), "key", "hash"));
    }

    private SkillVersionPublicationPolicy.Plan plan(
            String type, SkillVersionDO release, SkillVersionDO source) {
        var numbering =
                new SkillVersionRules.Numbering(
                        release == null ? 1 : 2,
                        "DRAFT".equals(type) ? 1 : 0,
                        2L,
                        release == null ? "v1" : "DRAFT".equals(type) ? "v2-draft.1" : "v2");
        var metadata =
                codec.versionMetadata(
                        SkillTestFixture.metadata(),
                        numbering.label(),
                        "private notes",
                        release != null);
        return new SkillVersionPublicationPolicy.Plan(
                numbering,
                type,
                SkillTestFixture.artifact(),
                metadata,
                release,
                release,
                source,
                List.of(
                        new SkillFileChangeRespDTO(
                                "SKILL.md",
                                "ADDED",
                                null,
                                new SkillFileRespDTO("SKILL.md", 3L, "a"))),
                "digest");
    }
}
