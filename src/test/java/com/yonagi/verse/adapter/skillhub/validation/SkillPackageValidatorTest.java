package com.yonagi.verse.adapter.skillhub.validation;

import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPackageValidatorTest {
    private static final String MARKDOWN = "---\nname: test-skill\ndescription: 测试包\n---\n# 测试\n";
    private final SkillPackageValidator validator = new SkillPackageValidator();

    @Test
    void cancellationAtRejectionReturnCannotResetTheOriginalExecutionBudget() {
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        SkillInProcessInspector inspector = new SkillInProcessInspector();
        assertThrows(com.yonagi.verse.common.convention.exception.ServerException.class,
                () -> inspector.inspect(utf8("invalid"), () -> checks.incrementAndGet() < 5));
        assertEquals(5, checks.get());
    }

    @Test
    void cancellationAcrossExtractionScanningDigestAndNormalizationNeverReturnsAnArchive() throws Exception {
        byte[] input = zip(Map.of("SKILL.md", utf8(MARKDOWN), "payload.bin", new byte[32 * 1024]));
        java.util.concurrent.atomic.AtomicInteger total = new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(validator.validate(input, new SkillValidationControl(() -> {
            total.incrementAndGet();
            return true;
        })).success());
        // 在本样本的所有资格检查处逐一撤销，覆盖解包、内容扫描、摘要及重新压缩阶段。
        for (int stop = 1; stop < total.get(); stop++) {
            int boundary = stop;
            java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(SkillValidationCancelledException.class, () -> validator.validate(input,
                    new SkillValidationControl(() -> checks.incrementAndGet() < boundary)));
        }
    }

    @Test
    void inProcessBoundaryDistinguishesContentRejectionFromCancelledExecution() throws Exception {
        SkillInProcessInspector inspector = new SkillInProcessInspector();
        byte[] valid = zip(Map.of("SKILL.md", utf8(MARKDOWN)));
        assertTrue(inspector.inspect(valid, () -> true).success());
        assertEquals("INVALID_ARCHIVE", inspector.inspect(utf8("not a ZIP"), () -> true).code());
        byte[] brokenDeflate = valid.clone();
        ByteBuffer header = ByteBuffer.wrap(brokenDeflate).order(ByteOrder.LITTLE_ENDIAN);
        int payload = 30 + Short.toUnsignedInt(header.getShort(26)) + Short.toUnsignedInt(header.getShort(28));
        brokenDeflate[payload] = (byte) 0xff;
        assertEquals("INVALID_ARCHIVE", inspector.inspect(brokenDeflate, () -> true).code());
        assertEquals("LIMIT_EXCEEDED", inspector.inspect(null, () -> true).code());
        assertEquals("SECRET_DETECTED", inspector.inspect(zip(Map.of("SKILL.md",
                utf8(MARKDOWN), ".env", utf8("fixture"))), () -> true).code());
        assertThrows(com.yonagi.verse.common.convention.exception.ServerException.class,
                () -> inspector.inspect(valid, () -> false));
        inspector.close();
        assertThrows(com.yonagi.verse.common.convention.exception.ServerException.class,
                () -> inspector.inspect(valid, () -> true));
    }

    @Test
    void validatesUtf8ResourceLinksWithoutCopyingWholeMarkdown() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", utf8(MARKDOWN));
        files.put("README.md", utf8("文".repeat(100_000) + "[资源](assets/说明.txt)\n[参考]: assets/说明.txt\n"));
        files.put("assets/说明.txt", utf8("资源"));
        assertEquals(3, validator.validate(zip(files)).files().size());
        files.put("README.md", new byte[]{(byte) 0xe4, (byte) 0xb8});
        rejects("INVALID_SKILL_MARKDOWN", files);
        files.put("README.md", utf8("[超长](https://example.com/" + "a".repeat(8192) + ")"));
        rejects("INVALID_RESOURCE_REFERENCE", files);
        files.put("README.md", utf8("[缺失](assets/不存在.txt)"));
        rejects("INVALID_RESOURCE_REFERENCE", files);
    }

    @Test
    void normalizesRootAndWrappedLayoutWithoutChangingResourceBytes() throws Exception {
        byte[] resource = new byte[]{0, 1, 2, 127, -1};
        Map<String, byte[]> root = new LinkedHashMap<>();
        root.put("SKILL.md", utf8(MARKDOWN + "[资源](assets/file.bin)\n"));
        root.put("assets/file.bin", resource);
        Map<String, byte[]> wrapped = new LinkedHashMap<>();
        root.forEach((path, bytes) -> wrapped.put("test-skill/" + path, bytes));
        SkillValidationReport first = validator.validate(zip(root));
        SkillValidationReport second = validator.validate(zip(wrapped));
        assertTrue(first.success());
        assertEquals("test-skill", first.packageName());
        assertEquals("测试包", first.packageDescription());
        assertArrayEquals(first.archive(), second.archive());
        // 迁移前真实子进程产物；更改执行位置不能改变归档字节或内容摘要。
        assertEquals("07a5254e37dda6fac317fbb1e78e62c36467508f5b13a7b74e63285408e629fc", first.archiveSha256());
        assertEquals("5e9e54a892d17e6c509dbc7b4ec164d93d832637a8c6c4c4464d6ca1442125f5", first.contentSha256());
        assertEquals(first.contentSha256(), second.contentSha256());
        assertEquals(2, first.files().size());
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(first.archive()))) {
            Map<String, byte[]> actual = new LinkedHashMap<>();
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                actual.put(entry.getName(), input.readAllBytes());
            }
            assertArrayEquals(resource, actual.get("test-skill/assets/file.bin"));
            assertArrayEquals(root.get("SKILL.md"), actual.get("test-skill/SKILL.md"));
        }
    }

    @Test
    void rejectsMultipleCandidatesAndFilesOutsideWrapper() throws Exception {
        rejects("SKILL_COUNT_INVALID", Map.of("a/SKILL.md", utf8(MARKDOWN), "b/SKILL.md", utf8(MARKDOWN)));
        rejects("INVALID_LAYOUT", Map.of("test-skill/SKILL.md", utf8(MARKDOWN), "extra.txt", utf8("outside")));
        rejects("INVALID_LAYOUT", Map.of("a/test-skill/SKILL.md", utf8(MARKDOWN)));
        rejects("SKILL_COUNT_INVALID", Map.of("skill.md", utf8(MARKDOWN)));
    }

    @Test
    void rejectsTraversalDeviceNamesAndUnicodeAliases() throws Exception {
        for (String unsafe : new String[]{"../outside", "/absolute", "C:/drive", "dir\\escape", "CON.txt",
                "dir/../file", "dir//file", "file. ", "cafe\u0301.txt"}) {
            rejects("UNSAFE_PATH", Map.of("SKILL.md", utf8(MARKDOWN), unsafe, utf8("x")));
        }
        rejects("PATH_COLLISION", Map.of("SKILL.md", utf8(MARKDOWN), "A/x.txt", utf8("x"), "a/y.txt", utf8("y")));
        rejects("PATH_COLLISION", Map.of("SKILL.md", utf8(MARKDOWN), "resource", utf8("x"),
                "resource/x.txt", utf8("y")));
    }

    @Test
    void rejectsSymlinkWithoutFollowingIt() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(bytes)) {
            ZipArchiveEntry link = new ZipArchiveEntry("SKILL.md");
            link.setUnixMode(0120777);
            output.putArchiveEntry(link);
            output.write(utf8("/etc/passwd"));
            output.closeArchiveEntry();
        }
        SkillPackageValidationException failure = assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(bytes.toByteArray()));
        assertEquals("UNSUPPORTED_ENTRY", failure.getCode());
    }

    @Test
    void rejectsUnsafeYamlAndInvalidRequiredFields() throws Exception {
        for (String frontmatter : new String[]{"name: test-skill\nname: test-skill\ndescription: d",
                "name: &n test-skill\ndescription: *n", "!!javax.script.ScriptEngineManager {}",
                "name: Test\ndescription: d", "name: test--skill\ndescription: d",
                "name: test-skill\ndescription: 123", "name: test-skill\ndescription: d\nunknown: true"}) {
            rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md", utf8("---\n" + frontmatter + "\n---\n")));
        }
        rejects("INVALID_SKILL_MARKDOWN", Map.of("other-name/SKILL.md", utf8(MARKDOWN)));
        rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md", new byte[]{-1, -2}));
    }

    @Test
    void rejectsMissingAndEscapingResourcesButAllowsUnfetchedRemoteLinks() throws Exception {
        for (String link : new String[]{"missing.txt", "../outside", "%2e%2e/outside", "file:///secret",
                "javascript:alert", "//other.example/a"}) {
            rejects("INVALID_RESOURCE_REFERENCE", Map.of("SKILL.md", utf8(MARKDOWN + "[x](" + link + ")")));
        }
        SkillValidationReport result = validator.validate(zip(Map.of("SKILL.md",
                utf8(MARKDOWN + "[x](https://example.invalid/missing)\n[x](#heading)"))));
        assertTrue(result.success());
    }

    @Test
    void rejectsSecretFilesAndContentWithoutEchoingValue() throws Exception {
        rejects("SECRET_DETECTED", Map.of("SKILL.md", utf8(MARKDOWN), ".env", utf8("SECRET=abc")));
        String secret = "-----BEGIN OPENSSH PRIVATE KEY-----";
        SkillPackageValidationException failure = assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(zip(Map.of("SKILL.md", utf8(MARKDOWN), "notes.txt", utf8(secret)))));
        assertEquals("SECRET_DETECTED", failure.getCode());
        assertFalse(failure.getMessage().contains(secret));
        byte[] boundary = new byte[64 * 1024 + 128];
        java.util.Arrays.fill(boundary, (byte) 'a');
        byte[] header = utf8(secret);
        System.arraycopy(header, 0, boundary, 64 * 1024 - 10, header.length);
        rejects("SECRET_DETECTED", Map.of("SKILL.md", utf8(MARKDOWN), "boundary.bin", boundary));
        rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md", utf8(MARKDOWN + "\u0001")));
    }

    @Test
    void rejectsFileCountAndActualDecompressionLimit() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", utf8(MARKDOWN));
        for (int index = 0; index < 500; index++) {
            files.put("files/" + index + ".txt", utf8("x"));
        }
        rejects("LIMIT_EXCEEDED", files);
        byte[] bomb = new byte[50 * 1024 * 1024];
        rejects("LIMIT_EXCEEDED", Map.of("SKILL.md", utf8(MARKDOWN), "large.bin", bomb));
        byte[] lyingSize = zip(Map.of("SKILL.md", utf8(MARKDOWN), "large.bin", new byte[50 * 1024 * 1024 + 1]));
        patchCentralField(lyingSize, "large.bin", 24, 1);
        SkillPackageValidationException overflow = assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(lyingSize));
        assertEquals("LIMIT_EXCEEDED", overflow.getCode());
    }

    @Test
    void normalizedLongPathsCanBeImportedAgainWithSameContent() throws Exception {
        String name = "a".repeat(64);
        String longPath = ("d".repeat(55) + "/").repeat(9) + "f";
        String markdown = MARKDOWN.replace("test-skill", name);
        SkillValidationReport first = validator.validate(zip(Map.of("SKILL.md", utf8(markdown), longPath, utf8("x"))));
        SkillValidationReport imported = validator.validate(first.archive());
        assertEquals(first.contentSha256(), imported.contentSha256());
        assertArrayEquals(first.archive(), imported.archive());
    }

    @Test
    void invalidChecksumCannotBecomeValidNormalizedArchive() throws Exception {
        byte[] archive = zip(Map.of("SKILL.md", utf8(MARKDOWN)));
        patchCentralField(archive, "SKILL.md", 16, 0);
        SkillPackageValidationException failure = assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(archive));
        assertEquals("INVALID_ARCHIVE", failure.getCode());
    }

    @Test
    void emptyInputAndMalformedZipCannotBeAccepted() {
        assertThrows(SkillPackageValidationException.class, () -> validator.validate(null));
        assertThrows(SkillPackageValidationException.class, () -> validator.validate(new byte[0]));
        assertEquals("INVALID_ARCHIVE", assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(utf8("not a ZIP"))).getCode());
    }

    @Test
    void validatesOptionalDeclarationsWithoutAcceptingUntypedOrOversizedYaml() throws Exception {
        String prefix = "name: test-skill\ndescription: test\n";
        for (String extra : new String[]{"license: MIT\ncompatibility: Java 21\nallowed-tools: Read\n"
                + "metadata: {author: sample}", "metadata: {}"}) {
            assertTrue(validator.validate(zip(Map.of("SKILL.md", utf8("---\n" + prefix + extra + "\n---\n"))))
                    .success());
        }
        for (String invalid : new String[]{"license: []", "license: ''", "metadata: []",
                "metadata: {author: 123}", "metadata: {123: text}", "description: ''",
                "compatibility: " + "a".repeat(501)}) {
            rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md", utf8("---\n" + prefix + invalid + "\n---\n")));
        }
        rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md", utf8("no frontmatter")));
        rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md", utf8("---\n- list\n---\n")));
        rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md", utf8(MARKDOWN + "a".repeat(1024 * 1024))));
        rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md",
                utf8("---\n" + prefix + "#" + "a".repeat(64 * 1024) + "\n---\n")));
    }

    @Test
    void acceptsAllowedToolsStringsAndListsWithoutChangingMarkdownBytes() throws Exception {
        String prefix = "---\nname: test-skill\ndescription: |-\n  测试工具声明\n";
        for (String declaration : new String[]{"", "allowed-tools: Read\n",
                "allowed-tools: Read Grep Glob Bash WebFetch\n", "allowed-tools: Read, Grep, Glob\n",
                "allowed-tools: [Read, Grep, Glob]\n",
                "allowed-tools:\n  - Read\n  - Grep\n  - Glob\n"
                        + "  - Bash      # 验证代码质量\n  - WebFetch  # 查阅文档\n"}) {
            String markdown = (prefix + declaration + "---\n# 测试\n").replace("\n", "\r\n");
            byte[] original = utf8(markdown);
            SkillValidationReport result = validator.validate(zip(Map.of("SKILL.md", original)));
            assertTrue(result.success(), declaration);
            assertEquals("test-skill", result.packageName());
            assertEquals("测试工具声明", result.packageDescription());
            assertEquals(markdown, result.skillMarkdown());
            try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(result.archive()))) {
                assertEquals("test-skill/SKILL.md", input.getNextEntry().getName());
                assertArrayEquals(original, input.readAllBytes());
            }
            SkillValidationReport imported = validator.validate(result.archive());
            assertEquals(result.contentSha256(), imported.contentSha256());
            assertArrayEquals(result.archive(), imported.archive());
        }
    }

    @Test
    void rejectsEmptyOrNonStringAllowedToolsDeclarations() throws Exception {
        String prefix = "---\nname: test-skill\ndescription: test\nallowed-tools: ";
        for (String invalid : new String[]{"[]", "''", "'  '", "null", "true", "123", "{}",
                "[Read, '']", "[Read, '  ']", "[Read, null]", "[Read, true]", "[Read, 123]",
                "[Read, 1.5]", "[Read, [Grep]]", "[Read, {tool: Bash}]"}) {
            rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md", utf8(prefix + invalid + "\n---\n")));
        }
    }

    @Test
    void rejectsUnsafeOrOversizedYamlWhenAllowedToolsIsAList() throws Exception {
        String prefix = "---\nname: test-skill\ndescription: test\n";
        for (String invalid : new String[]{"allowed-tools: &tools [Read]\n",
                "allowed-tools: [&tool Read, *tool]\n",
                "allowed-tools: [Read]\nallowed-tools: [Bash]\n",
                "allowed-tools: [Read]\n#" + "a".repeat(64 * 1024) + "\n"}) {
            rejects("INVALID_SKILL_MARKDOWN", Map.of("SKILL.md", utf8(prefix + invalid + "---\n")));
        }
    }

    @Test
    void ignoresLinkLikeCodeInFencedExamplesButPreservesMarkdown() throws Exception {
        String code = "auto filtered = std::views::filter([](int value) { return value > 0; });\n"
                + "[K in keyof T]: T[K]\n[example](missing.txt)\n[ref]: missing.txt\n";
        for (String markdown : new String[]{"```cpp\n" + code + "```\n",
                "  ~~~~typescript\r\n" + code.replace("\n", "\r\n") + "   ~~~~~ \t\r\n",
                "````text\r" + code.replace("\n", "\r") + "```\r" + code + "````\r",
                "```text\n" + code + "~~~\n" + code + "```\n",
                "```text\n" + code + "``` trailing\n" + code + "```\n",
                "~~~text\n" + code}) {
            byte[] original = utf8(markdown);
            SkillValidationReport result = validator.validate(zip(Map.of("SKILL.md", utf8(MARKDOWN),
                    "reference/example.md", original)));
            assertTrue(result.success());
            SkillValidatedFile resource = result.files().stream()
                    .filter(file -> file.relativePath().equals("reference/example.md")).findFirst().orElseThrow();
            assertEquals(SkillPackageValidator.hash(original), resource.sha256());
        }
    }

    @Test
    void validatesRealLinksAroundFencesAndDoesNotTreatIndentedOrInvalidFencesAsOpeners() throws Exception {
        for (String markdown : new String[]{"[bad](missing.txt)\n```\ncode\n```\n",
                "```\ncode\n```\n[bad](missing.txt)\n", "```\ncode\n```\n[ref]: missing.txt\n",
                "```\ncode\n```\n[bad](../outside)\n", "    ```\n[bad](missing.txt)\n",
                "``\n[bad](missing.txt)\n", "```bad`info\n[bad](missing.txt)\n"}) {
            rejects("INVALID_RESOURCE_REFERENCE", Map.of("SKILL.md", utf8(MARKDOWN + markdown)));
        }
        String markdown = MARKDOWN + "[before](resource.txt)\n```\n[example](missing.txt)\n```\n"
                + "[after](resource.txt)\n[ref]: resource.txt\n";
        assertTrue(validator.validate(zip(Map.of("SKILL.md", utf8(markdown), "resource.txt", utf8("resource"))))
                .success());
    }

    @Test
    void scansSecretsInsideFencedCodeExamples() throws Exception {
        rejects("SECRET_DETECTED", Map.of("SKILL.md", utf8(MARKDOWN + "```text\n"
                + "-----BEGIN OPENSSH PRIVATE KEY-----\n```\n")));
    }

    @Test
    void rejectsCrossPlatformPathLimitsAndInvisibleAliases() throws Exception {
        for (String path : new String[]{"a".repeat(129), "a/".repeat(11) + "x", "a".repeat(578),
                "dir/./x", "dir/ ", "bad?name", "hidden\u200bname", "bad\u0001name", "trailing.", "aux.txt"}) {
            rejects("UNSAFE_PATH", Map.of("SKILL.md", utf8(MARKDOWN), path, utf8("x")));
        }
        rejects("UNSAFE_PATH", Map.of("SKILL.md", utf8(MARKDOWN), "a/".repeat(10) + "x", utf8("x")));
        assertTrue(validator.validate(zip(Map.of("SKILL.md", utf8(MARKDOWN), "empty/", new byte[0])))
                .success());
    }

    @Test
    void resolvesNestedAndEncodedReferencesWhileRejectingUnsafeDecodedTargets() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", utf8(MARKDOWN + "[http](http://example.invalid) [mail](mailto:x@example.invalid)"));
        files.put("docs/README.md", utf8("[up](../assets/%E8%AF%B4%E6%98%8E.txt) [dir](../assets)\n"
                + "[same](./../assets/说明.txt#part)"));
        files.put("assets/说明.txt", utf8("resource"));
        assertTrue(validator.validate(zip(files)).success());
        for (String target : new String[]{"%zz", "?query", "/absolute", "assets%5cfile", "assets/%00"}) {
            rejects("INVALID_RESOURCE_REFERENCE", Map.of("SKILL.md", utf8(MARKDOWN + "[bad](" + target + ")")));
        }
    }

    @Test
    void utf8ContinuationByteIsNotAMarkdownLineBoundary() throws Exception {
        assertTrue(validator.validate(zip(Map.of("SKILL.md", utf8(MARKDOWN),
                "README.md", utf8("丅[x]: missing.txt")))).success());
        rejects("INVALID_RESOURCE_REFERENCE", Map.of("SKILL.md", utf8(MARKDOWN),
                "README.md", utf8("正文\r[x]: missing.txt")));
        rejects("INVALID_RESOURCE_REFERENCE", Map.of("SKILL.md", utf8(MARKDOWN),
                "README.md", utf8("正文\r\n[x]: missing.txt")));
    }

    @Test
    void rejectsCredentialLocationsAndBinaryEmbeddedSecretSignatures() throws Exception {
        for (String location : new String[]{".git/config", ".ssh/config", ".aws/credentials", ".env.local",
                "id_rsa", "id_ed25519", ".npmrc"}) {
            rejects("SECRET_DETECTED", Map.of("SKILL.md", utf8(MARKDOWN), location, utf8("fixture")));
        }
        for (String signature : new String[]{"AKIA" + "A".repeat(16), "ghp_" + "a".repeat(20),
                "github_pat_" + "a".repeat(20), "sk-proj-" + "a".repeat(32),
                "-----BEGIN PGP PRIVATE KEY BLOCK-----"}) {
            rejects("SECRET_DETECTED", Map.of("SKILL.md", utf8(MARKDOWN), "binary.dat", utf8(signature)));
        }
        rejects("INVALID_TEXT", Map.of("SKILL.md", utf8(MARKDOWN), "script.py", new byte[]{0}));
    }

    private void patchCentralField(byte[] archive, String filename, int offset, int value) {
        ByteBuffer view = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index + 46 < archive.length; index++) {
            if (view.getInt(index) == 0x02014b50) {
                int nameLength = Short.toUnsignedInt(view.getShort(index + 28));
                if (index + 46 + nameLength <= archive.length
                        && new String(archive, index + 46, nameLength, StandardCharsets.UTF_8).equals(filename)) {
                    view.putInt(index + offset, value);
                    return;
                }
            }
        }
        throw new AssertionError("missing central directory entry");
    }

    private void rejects(String code, Map<String, byte[]> files) throws Exception {
        byte[] archive = zip(files);
        SkillPackageValidationException failure = assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(archive), files.keySet().toString());
        assertEquals(code, failure.getCode());
    }

    private byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                output.putNextEntry(new ZipEntry(file.getKey()));
                output.write(file.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
