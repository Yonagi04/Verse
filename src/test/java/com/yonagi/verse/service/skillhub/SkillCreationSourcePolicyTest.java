package com.yonagi.verse.service.skillhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillCreationSourcePolicyTest {
    private final SkillVersionMapper versions = mock(SkillVersionMapper.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillGithubSnapshotCodec json = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillCreationSourcePolicy policy =
            new SkillCreationSourcePolicy(versions, artifacts, json, new SkillGithubSourcePolicy());
    private SkillOwnedDraft owned;
    private SkillArtifactDO artifact;

    @BeforeEach
    void setup() {
        owned =
                new SkillOwnedDraft(
                        SkillTestFixture.publisher(),
                        SkillTestFixture.skill(),
                        SkillTestFixture.draft());
        owned.skill().setStatus("ACTIVE");
        owned.skill().setCurrentVersionId(70L);
        owned.skill().setCreationVersionId(70L);
        SkillVersionDO version = new SkillVersionDO();
        version.setVersionId(70L);
        version.setSkillId(20L);
        version.setArtifactId(40L);
        version.setVersionType("RELEASE");
        when(versions.selectById(70L)).thenReturn(version);
        artifact = SkillTestFixture.artifact();
        when(artifacts.selectById(40L)).thenReturn(artifact);
    }

    @Test
    void zipAndGithubSourcesCannotBeSwitched() {
        artifact.setSourceType("ZIP");
        policy.validate(owned.skill(), "ZIP", null);
        assertThrows(ClientException.class, () -> policy.validate(owned.skill(), "GITHUB", "{}"));
        bind("DEFAULT", "main", "a".repeat(40), "skills/review");
        assertThrows(ClientException.class, () -> policy.validate(owned.skill(), "ZIP", null));
    }

    @Test
    void originalDefaultBranchIsNowExplicitAndOnlyItsCommitMayAdvance() {
        bind("DEFAULT", "original-main", "a".repeat(40), "skills/review");
        var request = policy.request(owned.skill(), 2L);
        assertEquals("BRANCH", request.refType());
        assertEquals("original-main", request.ref());
        assertEquals("skills/review", request.directory());
        policy.validate(
                owned.skill(),
                "GITHUB",
                json.encode(
                        source("1", "BRANCH", "original-main", "b".repeat(40), "skills/review"),
                        8192));
        for (SkillSourceDTO invalid :
                java.util.List.of(
                        source("2", "BRANCH", "original-main", "b".repeat(40), "skills/review"),
                        source("1", "BRANCH", "new-main", "b".repeat(40), "skills/review"),
                        source("1", "BRANCH", "original-main", "b".repeat(40), "other"),
                        source("1", "TAG", "original-main", "b".repeat(40), "skills/review"))) {
            assertThrows(
                    ClientException.class,
                    () -> policy.validate(owned.skill(), "GITHUB", json.encode(invalid, 8192)));
        }
    }

    @Test
    void tagAndCommitRemainFixedAndMissingAnchorNeverGuessesDefaults() {
        bind("TAG", "stable", "a".repeat(40), "");
        assertEquals("stable", policy.request(owned.skill(), 2L).ref());
        bind("COMMIT", "a".repeat(40), "a".repeat(40), "");
        assertEquals("a".repeat(40), policy.request(owned.skill(), 2L).ref());
        owned.skill().setCreationVersionId(null);
        assertThrows(ServerException.class, () -> policy.source(owned.skill()));
    }

    @Test
    void downlistedStaleOrPromotionWorkspaceCannotPrepareNewContent() {
        policy.requirePreparing(owned);
        owned.draft().setSourceVersionId(71L);
        assertThrows(ClientException.class, () -> policy.requirePreparing(owned));
        owned.draft().setSourceVersionId(null);
        owned.draft().setBaseSkillRevision(9L);
        assertThrows(ClientException.class, () -> policy.requirePreparing(owned));
        owned.draft().setBaseSkillRevision(0L);
        owned.skill().setStatus("OWNER_UNPUBLISHED");
        assertThrows(ClientException.class, () -> policy.requirePreparing(owned));
    }

    private void bind(String type, String ref, String sha, String directory) {
        artifact.setSourceType("GITHUB");
        artifact.setSourceJson(json.encode(source("1", type, ref, sha, directory), 8192));
    }

    private SkillSourceDTO source(
            String id, String type, String ref, String sha, String directory) {
        return new SkillSourceDTO(
                1, "GITHUB", "https://github.com/owner/repo", id, ref, type, sha, directory);
    }
}
