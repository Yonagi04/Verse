package com.yonagi.verse.adapter.skillhub.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.service.skillhub.port.SkillGithubSourceException;
import com.yonagi.verse.service.skillhub.port.SkillImportBudget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 样本仅验证实际解析/转换算法，不用假的GitHub或S3证明外部可用性。 */
class SkillRepositorySnapshotInspectorTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SkillRepositorySnapshotInspector inspector = new SkillRepositorySnapshotInspector(mapper);

    @Test
    void discoversMultipleSkillsAndExtractsOnlyCompleteSelectedSubtreeWithoutChangingBytes() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("skills/one/SKILL.md", markdown("one", "[resource](assets/value.txt)"));
        files.put("skills/one/assets/value.txt", utf8("原始资源\r\n"));
        files.put("skills/one/scripts/run.py", utf8("print('never execute')"));
        files.put("skills/two/SKILL.md", markdown("two", "# second"));
        files.put("README.md", utf8("outside"));
        Path archive = archive(files);
        byte[] tree = tree(files, Map.of());
        List<SkillGithubCandidateDTO> candidates = inspector.discover(archive, tree, "", "digest", budget());
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().allMatch(SkillGithubCandidateDTO::selectable));
        byte[] selected = inspector.extract(archive, tree, candidates.getFirst(), budget());
        var report = new SkillPackageValidator().validate(selected);
        assertEquals("one", report.packageName());
        assertEquals(3, report.files().size());
        assertTrue(report.files().stream().noneMatch(value -> value.relativePath().contains("two")
                || value.relativePath().equals("README.md")));
        assertEquals(cn.hutool.crypto.digest.DigestUtil.sha256Hex(files.get("skills/one/assets/value.txt")),
                report.files().stream().filter(value -> value.relativePath().equals("assets/value.txt"))
                        .findFirst().orElseThrow().sha256());
        assertNotEquals(cn.hutool.crypto.digest.DigestUtil.sha256Hex(Files.readAllBytes(archive)), report.sourceSha256());
    }

    @Test
    void invalidAndNestedCandidatesStayVisibleAndCannotBeSilentlyMerged() throws Exception {
        Map<String, byte[]> files = Map.of("SKILL.md", markdown("outer", ""),
                "one/SKILL.md", markdown("wrong-name", ""), "two/SKILL.md", markdown("two", ""));
        var candidates = inspector.discover(archive(files), tree(files, Map.of()), "", "digest", budget());
        assertEquals(3, candidates.size());
        assertFalse(candidates.getFirst().selectable());
        assertEquals("NESTED_SKILL", candidates.getFirst().issues().getFirst().code());
        assertFalse(candidates.get(1).selectable());
        assertTrue(candidates.get(2).selectable());
    }

    @Test
    void missingResourceIsRejectedAtTheSourceFileInsteadOfFetchingParentDirectory() throws Exception {
        Map<String, byte[]> files = Map.of("one/SKILL.md", markdown("one", "[missing](assets/missing.txt)"));
        Path archive = archive(files); byte[] tree = tree(files, Map.of());
        var candidate = inspector.discover(archive, tree, "one", "digest", budget()).getFirst();
        var report = new SkillInProcessInspector().inspect(inspector.extract(archive, tree, candidate, budget()), () -> true);
        assertFalse(report.success());
        assertEquals("INVALID_RESOURCE_REFERENCE", report.code());
        assertEquals("SKILL.md", report.issues().getFirst().path());
    }

    @Test
    void fixedTreeMissingArchiveFileAndAlteredBlobAreRejected() throws Exception {
        Map<String, byte[]> files = Map.of("one/SKILL.md", markdown("one", ""), "one/a.txt", utf8("original"));
        byte[] tree = tree(files, Map.of());
        Path archive = archive(files);
        var candidate = inspector.discover(archive, tree, "", "digest", budget()).getFirst();
        Path missing = archive(Map.of("one/SKILL.md", markdown("one", "")));
        assertEquals("SOURCE_INTEGRITY_FAILED", assertThrows(SkillGithubSourceException.class,
                () -> inspector.extract(missing, tree, candidate, budget())).code());
        Path altered = archive(Map.of("one/SKILL.md", markdown("one", ""), "one/a.txt", utf8("changed")));
        assertEquals("SOURCE_INTEGRITY_FAILED", assertThrows(SkillGithubSourceException.class,
                () -> inspector.extract(altered, tree, candidate, budget())).code());
    }

    @Test
    void rejectsSubmodulesAndSymlinksWithinChosenSkillButDoesNotFollowUnrelatedLinks() throws Exception {
        Map<String, byte[]> files = Map.of("one/SKILL.md", markdown("one", ""), "one/link", utf8("../../secret"),
                "two/SKILL.md", markdown("two", ""));
        for (String mode : List.of("120000", "160000")) {
            var candidates = inspector.discover(archive(files), tree(files, Map.of("one/link", mode)), "", "digest", budget());
            assertFalse(candidates.getFirst().selectable());
            assertEquals("UNSUPPORTED_GIT_ENTRY", candidates.getFirst().issues().getFirst().code());
            assertTrue(candidates.getLast().selectable());
        }
    }

    @Test
    void lfsPointersAndTruncatedTreesCannotBecomeNormalFiles() throws Exception {
        Map<String, byte[]> files = Map.of("one/SKILL.md", markdown("one", ""), "one/a.bin",
                utf8("version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 4\n"));
        Path archive = archive(files); byte[] tree = tree(files, Map.of());
        var candidate = inspector.discover(archive, tree, "", "digest", budget()).getFirst();
        assertEquals("UNSUPPORTED_GIT_ENTRY", assertThrows(SkillGithubSourceException.class,
                () -> inspector.extract(archive, tree, candidate, budget())).code());
        ObjectNode truncated = (ObjectNode) mapper.readTree(tree); truncated.put("truncated", true);
        assertEquals("SOURCE_LIMIT_EXCEEDED", assertThrows(SkillGithubSourceException.class,
                () -> inspector.discover(archive, mapper.writeValueAsBytes(truncated), "", "digest", budget())).code());
    }

    @Test
    void noSkillTooManyMatchesAndCancelledBudgetNeverReturnPartialCandidates() throws Exception {
        Map<String, byte[]> empty = Map.of("README.md", utf8("no skill"));
        assertEquals("NO_SKILL", assertThrows(SkillGithubSourceException.class,
                () -> inspector.discover(archive(empty), tree(empty, Map.of()), "", "digest", budget())).code());
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int index = 0; index < 101; index++) many.put("s" + index + "/SKILL.md", markdown("s" + index, ""));
        Path archive = archive(many); byte[] tree = tree(many, Map.of());
        assertEquals("SOURCE_LIMIT_EXCEEDED", assertThrows(SkillGithubSourceException.class,
                () -> inspector.discover(archive, tree, "", "digest", budget())).code());
        assertEquals("SOURCE_TIMEOUT", assertThrows(SkillGithubSourceException.class,
                () -> inspector.discover(archive, tree, "", "digest", new SkillImportBudget(Instant.now(), () -> false))).code());
    }

    @Test
    void metadataAndSecretErrorsExposeOnlyFileLocations() throws Exception {
        String secret = "ghp_" + "A".repeat(40);
        Map<String, byte[]> files = Map.of("one/SKILL.md", markdown("one", ""), "one/token.txt", utf8(secret));
        Path archive = archive(files); byte[] tree = tree(files, Map.of());
        var candidate = inspector.discover(archive, tree, "", "digest", budget()).getFirst();
        var report = new SkillInProcessInspector().inspect(inspector.extract(archive, tree, candidate, budget()), () -> true);
        assertFalse(report.success());
        assertEquals("SECRET_DETECTED", report.code());
        assertEquals("token.txt", report.issues().getFirst().path());
        assertFalse(mapper.writeValueAsString(report.issues()).contains(secret));
    }

    private byte[] tree(Map<String, byte[]> files, Map<String, String> modes) throws Exception {
        ObjectNode root = mapper.createObjectNode().put("truncated", false).put("sha", "a".repeat(40));
        ArrayNode entries = root.putArray("tree");
        for (var file : files.entrySet()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + file.getValue().length + "\0").getBytes(StandardCharsets.US_ASCII));
            String sha = HexFormat.of().formatHex(digest.digest(file.getValue()));
            entries.addObject().put("path", file.getKey()).put("mode", modes.getOrDefault(file.getKey(), "100644"))
                    .put("type", "blob").put("size", file.getValue().length).put("sha", sha);
        }
        return mapper.writeValueAsBytes(root);
    }

    private Path archive(Map<String, byte[]> files) throws Exception {
        Path path = Files.createTempFile(temporary, "source-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry("repo-sha/" + entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private byte[] markdown(String name, String body) { return utf8("---\nname: " + name + "\ndescription: 示例\n---\n" + body); }
    private byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private SkillImportBudget budget() { return new SkillImportBudget(Instant.now().plusSeconds(120), () -> true); }
}
