package com.yonagi.verse.service.skillhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.resp.SkillFileChangeRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/** 编号、比较基准和确认冻结的业务规则，不声称证明DB锁或回滚。 */
class SkillVersionPublicationPolicyTest {
    private final SkillVersionMapper versions = mock(SkillVersionMapper.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillPublicationPolicy initial = mock(SkillPublicationPolicy.class);
    private final SkillCreationSourcePolicy sources = mock(SkillCreationSourcePolicy.class);
    private final SkillGithubSnapshotCodec json = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillPublicationSnapshotCodec snapshots =
            new SkillPublicationSnapshotCodec(new ObjectMapper());
    private final SkillVersionPublicationPolicy policy =
            new SkillVersionPublicationPolicy(
                    versions, artifacts, json, snapshots, initial, sources);
    private SkillOwnedDraft owned;
    private SkillArtifactDO target;
    private SkillVersionDO release;
    private SkillVersionDO head;

    @BeforeEach
    void setup() {
        SkillTestFixture.initializeMapping(
                SkillDO.class, SkillVersionDO.class, SkillArtifactDO.class);
        owned =
                new SkillOwnedDraft(
                        SkillTestFixture.publisher(),
                        SkillTestFixture.skill(),
                        SkillTestFixture.draft());
        owned.draft().setStatus("READY");
        owned.draft().setArtifactId(40L);
        owned.draft().setMetadataJson(snapshots.encode(SkillTestFixture.metadata()));
        owned.draft().setReleaseNotes("本次更新");
        target = artifact(40L, "b");
        release = node(70L, "RELEASE", 1, 0, 1L, 41L);
        head = node(71L, "DRAFT", 2, 1, 2L, 42L);
        when(artifacts.selectList(any()))
                .thenAnswer(call -> List.of(target, artifact(41L, "a"), artifact(42L, "b")));
        when(versions.selectList(any())).thenAnswer(call -> List.of(release, head));
    }

    @Test
    void firstReleaseIsV1WithEmptyBaseAndRejectsDraftWithoutConsumingNumber() {
        var plan = policy.plan(owned, "RELEASE", false);
        assertEquals("v1", plan.numbering().label());
        assertEquals(1, plan.numbering().sequence());
        assertEquals(1, plan.count("ADDED"));
        assertNull(plan.base());
        assertThrows(ClientException.class, () -> policy.plan(owned, "DRAFT", false));
        assertEquals(0L, owned.skill().getVersionSequence());
        verify(initial).validateSubmission(plan.metadata());
    }

    @Test
    void draftsComparePreviousNodeWhileReleaseComparesPreviousReleaseAndInheritsEveryBaseField() {
        updating();
        var draft = policy.plan(owned, "DRAFT", false);
        var formal = policy.plan(owned, "RELEASE", false);
        assertEquals("v2-draft.2", draft.numbering().label());
        assertEquals(71L, draft.base().getVersionId());
        assertEquals(0, draft.count("MODIFIED"));
        assertEquals("v2", formal.numbering().label());
        assertEquals(70L, formal.base().getVersionId());
        assertEquals(1, formal.count("MODIFIED"));
        assertEquals(SkillTestFixture.metadata().name(), formal.metadata().name());
        assertEquals(SkillTestFixture.metadata().license(), formal.metadata().license());
        assertEquals(SkillTestFixture.metadata().dependencies(), formal.metadata().dependencies());
        assertFalse(formal.metadata().distributionAuthorized());
        assertFalse(formal.metadata().declarationsConfirmed());
        verifyNoInteractions(initial);
    }

    @Test
    void latestDraftPromotionUsesExactArtifactAndDoesNotPermitDraftOfDraftOrOlderSource() {
        updating();
        owned.draft().setSourceVersionId(71L);
        owned.draft().setArtifactId(42L);
        var formal = policy.plan(owned, "RELEASE", true);
        assertEquals(71L, formal.source().getVersionId());
        assertEquals(42L, formal.artifact().getArtifactId());
        assertThrows(ClientException.class, () -> policy.plan(owned, "DRAFT", false));
        owned.draft().setSourceVersionId(69L);
        assertThrows(ClientException.class, () -> policy.plan(owned, "RELEASE", false));
        owned.draft().setSourceVersionId(71L);
        head.setStatus("BLOCKED");
        assertThrows(ClientException.class, () -> policy.plan(owned, "RELEASE", false));
    }

    @Test
    void staleRoundBaseBlockedAndMissingManifestNeverBecomeEmptyChanges() {
        updating();
        owned.draft().setBaseSkillRevision(0L);
        assertThrows(ClientException.class, () -> policy.plan(owned, "RELEASE", false));
        owned.draft().setBaseSkillRevision(2L);
        release.setStatus("BLOCKED");
        assertThrows(ClientException.class, () -> policy.plan(owned, "RELEASE", false));
        release.setStatus("AVAILABLE");
        target.setManifestJson("{}");
        assertThrows(ServerException.class, () -> policy.plan(owned, "RELEASE", false));
    }

    @Test
    void confirmationDigestChangesForNotesArtifactOrTargetButIsStableOnRepeatedRead() {
        updating();
        String digest = policy.plan(owned, "RELEASE", false).digest();
        assertEquals(digest, policy.plan(owned, "RELEASE", false).digest());
        assertNotEquals(digest, policy.plan(owned, "DRAFT", false).digest());
        owned.draft().setReleaseNotes("另一个说明");
        assertNotEquals(digest, policy.plan(owned, "RELEASE", false).digest());
        owned.draft().setReleaseNotes("本次更新");
        target.setContentSha256("d".repeat(64));
        assertNotEquals(digest, policy.plan(owned, "RELEASE", false).digest());
    }

    @Test
    void unavailableArtifactWrongOwnerInvalidTypeOrNotesAndDisabledSkillAreRejected() {
        updating();
        for (String status : List.of("OWNER_UNPUBLISHED", "PLATFORM_DISABLED")) {
            owned.skill().setStatus(status);
            assertThrows(ClientException.class, () -> policy.plan(owned, "RELEASE", false));
        }
        owned.skill().setStatus("ACTIVE");
        assertThrows(ClientException.class, () -> policy.plan(owned, null, false));
        assertThrows(ClientException.class, () -> policy.plan(owned, "OTHER", false));
        owned.draft().setReleaseNotes(" ");
        assertThrows(ClientException.class, () -> policy.plan(owned, "RELEASE", false));
        owned.draft().setReleaseNotes("x".repeat(2001));
        assertThrows(ClientException.class, () -> policy.plan(owned, "RELEASE", false));
        owned.draft().setReleaseNotes("本次更新");
        target.setOwnerUserId(2L);
        assertThrows(ClientException.class, () -> policy.plan(owned, "RELEASE", false));
        target.setOwnerUserId(1L);
        target.setStatus("DELETE_PENDING");
        assertThrows(ClientException.class, () -> policy.plan(owned, "RELEASE", false));
    }

    @Test
    void numberingIsContinuousAcrossRoundsAndRefusesOverflow() {
        updating();
        assertEquals(
                "v2-draft.2",
                SkillVersionRules.next(owned.skill(), release, head, "DRAFT").label());
        release.setReleaseNumber(2);
        head = release;
        assertEquals(
                "v3-draft.1",
                SkillVersionRules.next(owned.skill(), release, head, "DRAFT").label());
        owned.skill().setVersionSequence(Long.MAX_VALUE);
        assertThrows(
                ServerException.class,
                () -> SkillVersionRules.next(owned.skill(), release, head, "RELEASE"));
        owned.skill().setVersionSequence(2L);
        release.setReleaseNumber(Integer.MAX_VALUE);
        assertThrows(
                ServerException.class,
                () -> SkillVersionRules.next(owned.skill(), release, head, "RELEASE"));
    }

    @Test
    void manifestClassificationUsesRelativePathAndFileHashAndRejectsCorruptData() {
        var oldFiles =
                new SkillPackageManifest(
                        "outer-a",
                        "x",
                        List.of(
                                file("gone.txt", "a"),
                                file("same.txt", "b"),
                                file("edit.txt", "c")));
        var newFiles =
                new SkillPackageManifest(
                        "outer-b",
                        "y",
                        List.of(
                                file("new.txt", "d"),
                                file("same.txt", "b"),
                                file("edit.txt", "e")));
        assertEquals(
                List.of("MODIFIED", "DELETED", "ADDED", "UNCHANGED"),
                SkillManifestComparison.compare(oldFiles, newFiles).stream()
                        .map(SkillFileChangeRespDTO::changeType)
                        .toList());
        assertThrows(
                ServerException.class,
                () ->
                        SkillManifestComparison.compare(
                                null,
                                new SkillPackageManifest(
                                        "root",
                                        "x",
                                        List.of(file("duplicate", "a"), file("duplicate", "b")))));
    }

    private void updating() {
        owned.skill().setStatus("ACTIVE");
        owned.skill().setRevision(2L);
        owned.skill().setVersionSequence(2L);
        owned.skill().setCurrentVersionId(70L);
        owned.skill().setPublicRevisionId(90L);
        owned.draft().setBaseSkillRevision(2L);
    }

    private SkillValidatedFile file(String path, String sha) {
        return new SkillValidatedFile(path, sha.repeat(64), 3L);
    }

    private SkillArtifactDO artifact(Long id, String sha) {
        SkillArtifactDO value = SkillTestFixture.artifact();
        value.setArtifactId(id);
        value.setSourceType("ZIP");
        value.setManifestJson(
                json.encode(
                        new SkillPackageManifest("root", "x", List.of(file("SKILL.md", sha))),
                        8192));
        return value;
    }

    private SkillVersionDO node(
            Long id, String type, int release, int draft, long sequence, Long artifact) {
        SkillVersionDO value = new SkillVersionDO();
        value.setVersionId(id);
        value.setSkillId(20L);
        value.setVersionType(type);
        value.setReleaseNumber(release);
        value.setDraftNumber(draft);
        value.setSequenceNumber(sequence);
        value.setStatus("AVAILABLE");
        value.setArtifactId(artifact);
        value.setDeclarationsJson(snapshots.encode(SkillTestFixture.metadata()));
        return value;
    }
}
