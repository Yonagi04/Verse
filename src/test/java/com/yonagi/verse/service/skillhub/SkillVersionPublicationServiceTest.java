package com.yonagi.verse.service.skillhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillApprovalMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.req.SkillVersionPublishReqDTO;
import com.yonagi.verse.dto.resp.SkillVersionReceiptRespDTO;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

class SkillVersionPublicationServiceTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final String KEY = "12345678-1234-1234-1234-123456789abc";
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final SkillVersionWorkspaceService workspace = mock(SkillVersionWorkspaceService.class);
    private final SkillVersionPublicationPolicy policy = mock(SkillVersionPublicationPolicy.class);
    private final SkillVersionWriter writer = mock(SkillVersionWriter.class);
    private final SkillVersionMapper versions = mock(SkillVersionMapper.class);
    private final SkillApprovalMapper approvals = mock(SkillApprovalMapper.class);
    private final SkillIdempotencyService commands = mock(SkillIdempotencyService.class);
    private final SkillGithubSnapshotCodec json = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillVersionPublicationService service =
            new SkillVersionPublicationService(
                    access,
                    workspace,
                    policy,
                    writer,
                    versions,
                    approvals,
                    json,
                    commands,
                    VALIDATORS.getValidator());
    private final SkillVersionPublishReqDTO input =
            new SkillVersionPublishReqDTO(2L, 0L, "DRAFT", "a".repeat(64));
    private SkillOwnedDraft owned;

    @AfterAll
    static void close() {
        VALIDATORS.close();
    }

    @BeforeEach
    void setup() {
        SkillTestFixture.initializeMapping(SkillVersionDO.class);
        owned =
                new SkillOwnedDraft(
                        SkillTestFixture.publisher(),
                        SkillTestFixture.skill(),
                        SkillTestFixture.draft());
        when(workspace.read(1L, 20L)).thenReturn(owned);
        when(access.lockDraft(1L, 30L)).thenReturn(owned);
    }

    @Test
    void successfulPublicationUsesFixedRequestHashAndRecordsOnlyCommittedResult() {
        var plan = mock(SkillVersionPublicationPolicy.Plan.class);
        when(plan.digest()).thenReturn(input.confirmationDigest());
        when(policy.plan(owned, "DRAFT", true)).thenReturn(plan);
        var receipt =
                new SkillVersionReceiptRespDTO(
                        "20",
                        "71",
                        "DRAFT",
                        "v2-draft.1",
                        "2",
                        SkillTestFixture.now().atOffset(ZoneOffset.UTC),
                        null,
                        null,
                        null);
        when(writer.publish(eq(owned), eq(plan), eq(KEY), anyString())).thenReturn(receipt);
        assertSame(receipt, service.publish(1L, 20L, input, KEY.toUpperCase()));
        verify(commands)
                .record(
                        eq(1L),
                        eq("PUBLISH_SKILL_VERSION_V1"),
                        eq(KEY),
                        any(),
                        eq(71L),
                        eq(receipt));
    }

    @Test
    void persistentReplayPrecedesConsumedWorkspaceAndSurvivesShortReceiptDeletion() {
        SkillVersionDO version = committed();
        when(versions.selectOne(any())).thenReturn(version);
        var result = service.publish(1L, 20L, input, KEY);
        assertEquals("71", result.versionId());
        assertEquals("v2-draft.1", result.version());
        assertEquals(version.getPublishedAt().atOffset(ZoneOffset.UTC), result.publishedAt());
        verifyNoInteractions(workspace, policy, writer, commands);
        assertEquals("COMMITTED", service.result(1L, 20L, KEY).status());
    }

    @Test
    void keyCannotBeReusedAcrossPayloadOrSkill() {
        SkillVersionDO version = committed();
        when(versions.selectOne(any())).thenReturn(version);
        var changed = new SkillVersionPublishReqDTO(3L, 0L, "DRAFT", "a".repeat(64));
        assertEquals(
                SkillErrorCodeEnum.IDEMPOTENCY_CONFLICT.code(),
                assertThrows(ClientException.class, () -> service.publish(1L, 20L, changed, KEY))
                        .getErrorCode());
        version.setSkillId(21L);
        assertThrows(ClientException.class, () -> service.publish(1L, 20L, input, KEY));
    }

    @Test
    void staleRevisionOrDigestIsRejectedAndCannotReachWriter() {
        assertThrows(
                ClientException.class,
                () ->
                        service.publish(
                                1L,
                                20L,
                                new SkillVersionPublishReqDTO(1L, 0L, "DRAFT", "a".repeat(64)),
                                KEY));
        var plan = mock(SkillVersionPublicationPolicy.Plan.class);
        when(plan.digest()).thenReturn("changed");
        when(policy.plan(owned, "DRAFT", true)).thenReturn(plan);
        assertThrows(ClientException.class, () -> service.publish(1L, 20L, input, KEY));
        verifyNoInteractions(writer, commands);
    }

    @Test
    void unconfirmedResultIsNotFailureAndInvalidInputCannotAcquireResourceLocks() {
        assertEquals("UNCONFIRMED", service.result(1L, 20L, KEY).status());
        assertThrows(ClientException.class, () -> service.publish(1L, 20L, null, KEY));
        assertThrows(ClientException.class, () -> service.publish(1L, 20L, input, "bad-key"));
        verify(access, never()).lockSkill(any(), any());
    }

    private SkillVersionDO committed() {
        SkillVersionDO version = new SkillVersionDO();
        version.setSkillId(20L);
        version.setVersionId(71L);
        version.setVersionType("DRAFT");
        version.setReleaseNumber(2);
        version.setDraftNumber(1);
        version.setSequenceNumber(2L);
        version.setPublishedAt(SkillTestFixture.now());
        version.setPublishedBy(1L);
        version.setPublishRequestHash(
                DigestUtil.sha256Hex(
                        json.encode(
                                List.of(
                                        "PUBLISH_SKILL_VERSION_V1",
                                        "20",
                                        "2",
                                        "0",
                                        "DRAFT",
                                        "a".repeat(64)),
                                8192)));
        return version;
    }
}
