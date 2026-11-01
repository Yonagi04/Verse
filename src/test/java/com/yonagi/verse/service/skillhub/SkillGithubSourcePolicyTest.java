package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dto.req.SkillGithubImportCreateReqDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SkillGithubSourcePolicyTest {
    private final SkillGithubSourcePolicy policy = new SkillGithubSourcePolicy();

    @Test
    void canonicalizesRepositoryAndKeepsEmptyReferenceAsActualDefaultBranch() {
        SkillGithubSourceRequest source = parse("https://github.com/owner/repo.git/", null, null, null);
        assertEquals("https://github.com/owner/repo", source.repositoryUrl());
        assertEquals("DEFAULT", source.refType());
        assertNull(source.ref());
        assertEquals("", source.directory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://github.com/a/b", "https://github.com.evil.test/a/b", "https://evil.test/a/b",
            "https://user:pass@github.com/a/b", "https://github.com:8443/a/b", "https://127.0.0.1/a/b",
            "https://github.com/a/b?token=secret", "https://github.com/a/b#fragment", "https://github.com/a/b/blob/main/SKILL.md",
            "https://github.com/a/b/tree/main/../secret", "https://github.com/a/b/tree/main/%2e%2e/secret",
            "https://github.com/a/b/tree/main/%252e%252e/secret", "https://github.com/a/b/tree/main/%2fsecret",
            "https://github.com/a//b", "https://github.com/a/b/tree/main/%00bad", "git@github.com:a/b.git"})
    void rejectsUnsupportedOrCredentialledAndEncodedTraversalInputs(String url) {
        assertThrows(ClientException.class, () -> parse(url, null, null, null));
    }

    @Test
    void treeBranchWithSlashesProducesAllBoundedSplitsRatherThanGuessingFirstSegment() {
        SkillGithubSourceRequest source = parse("https://github.com/a/b/tree/feature/review/skills/one", null, null, null);
        List<SkillGithubSourceRequest> splits = policy.splits(source);
        assertEquals(4, splits.size());
        assertTrue(splits.stream().anyMatch(value -> value.ref().equals("feature/review")
                && value.directory().equals("skills/one")));
        assertTrue(splits.stream().anyMatch(value -> value.ref().equals("feature")
                && value.directory().equals("review/skills/one")));
    }

    @Test
    void explicitTreeSplitMustReconstructTheOriginalLink() {
        SkillGithubSourceRequest source = parse("https://github.com/a/b/tree/feature/review/skills/one",
                "BRANCH", "feature/review", "skills/one");
        assertEquals(1, policy.splits(source).size());
        assertThrows(ClientException.class, () -> parse("https://github.com/a/b/tree/main/skills/one",
                "BRANCH", "main", "other"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../main", "main..other", "a@{0}", "a b", ".hidden", "a.lock", "a//b", "main~1"})
    void rejectsRevisionExpressionsAndInvalidGitRefs(String ref) {
        assertThrows(ClientException.class, () -> parse("https://github.com/a/b", "BRANCH", ref, ""));
    }

    @Test
    void commitsRequireCompleteShaAndTooManyTreeSplitsAreRejected() {
        assertThrows(ClientException.class, () -> parse("https://github.com/a/b", "COMMIT", "abcdef0", ""));
        assertEquals("COMMIT", parse("https://github.com/a/b", "COMMIT", "a".repeat(40), "").refType());
        assertThrows(ClientException.class, () -> policy.splits(parse(
                "https://github.com/a/b/tree/a/b/c/d/e/f/g/h/i/j/k", null, null, null)));
    }

    private SkillGithubSourceRequest parse(String url, String type, String ref, String directory) {
        return policy.parse(new SkillGithubImportCreateReqDTO(0L, url, type, ref, directory));
    }
}
