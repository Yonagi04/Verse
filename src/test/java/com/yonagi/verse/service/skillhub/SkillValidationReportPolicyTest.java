package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 校验结果绑定前检查业务不变量，验证摘要、正文绑定、路径边界及失败脱敏。 */
class SkillValidationReportPolicyTest {
    private final SkillValidationReportPolicy policy = new SkillValidationReportPolicy();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsBoundedResultOnlyWhenMarkdownMatchesManifestHashAndSize() {
        SkillValidationReport valid = report(List.of(markdownFile()));
        assertSame(valid, policy.validate(valid));
        SkillValidationReport changedMarkdown = new SkillValidationReport(valid.sourceSha256(), true, null, null,
                valid.packageName(), valid.packageDescription(), "different content", valid.archive(),
                valid.archiveSha256(), valid.contentSha256(), valid.unpackedBytes(), valid.files());
        assertThrows(ServerException.class, () -> policy.validate(changedMarkdown));
    }

    @Test
    void wrongArchiveHashAndInvalidLengthsCannotBecomeReady() throws Exception {
        for (String field : List.of("sourceSha256", "archiveSha256", "contentSha256")) {
            ObjectNode value = mapper.valueToTree(report(List.of(markdownFile())));
            value.put(field, "invalid");
            rejected(value);
        }
        ObjectNode wrongHash = mapper.valueToTree(report(List.of(markdownFile())));
        wrongHash.put("archiveSha256", "c".repeat(64));
        rejected(wrongHash);
        for (String field : List.of("archive", "files", "skillMarkdown", "packageName", "packageDescription")) {
            ObjectNode value = mapper.valueToTree(report(List.of(markdownFile())));
            value.putNull(field);
            rejected(value);
        }
        assertThrows(ServerException.class, () -> policy.validate(null));
    }

    @Test
    void unsafeDuplicateOrMissingMarkdownPathsAndSizeLiesAreRejected() {
        for (String path : List.of("../escape", "/absolute", "a\\b", "C:/drive", "a//b", "a/../b", "NUL.txt",
                "trailing.", "trailing ", "a/\u0001", "cafe\u0301.txt", "a/".repeat(10) + "file")) {
            assertThrows(ServerException.class, () -> policy.validate(report(List.of(markdownFile(),
                    new SkillValidatedFile(path, "b".repeat(64), 0L)))));
        }
        assertThrows(ServerException.class, () -> policy.validate(report(List.of(markdownFile(), markdownFile()))));
        assertThrows(ServerException.class, () -> policy.validate(report(List.of(
                new SkillValidatedFile("other.md", "a".repeat(64), markdownFile().sizeBytes())))));
        assertThrows(ServerException.class, () -> policy.validate(report(List.of(
                new SkillValidatedFile("SKILL.md", "b".repeat(64), markdownFile().sizeBytes())))));
        assertThrows(ServerException.class, () -> policy.validate(report(List.of(
                new SkillValidatedFile("SKILL.md", markdownFile().sha256(), -1L)))));
    }

    @Test
    void rejectionCodesAreWhitelistedAndNeverEchoParserTextOrPayload() {
        SkillValidationReport denied = new SkillValidationReport("a".repeat(64), false, "SECRET_DETECTED",
                "private-token", null, null, null, null, null, null, 0L, List.of());
        policy.validate(denied);
        assertEquals("包内疑似包含秘密或本机凭据，请清理后重新上传", policy.reason(denied.code()));
        assertThrows(ServerException.class, () -> policy.reason("private-token"));
        SkillValidationReport injected = new SkillValidationReport("a".repeat(64), false, "NOT_WHITELISTED",
                "private-token", null, null, null, null, null, null, 0L, List.of());
        assertThrows(ServerException.class, () -> policy.validate(injected));
    }

    private void rejected(ObjectNode tree) throws Exception {
        SkillValidationReport report = mapper.treeToValue(tree, SkillValidationReport.class);
        assertThrows(ServerException.class, () -> policy.validate(report));
    }

    private SkillValidatedFile markdownFile() {
        byte[] bytes = "# 测试".getBytes(StandardCharsets.UTF_8);
        return new SkillValidatedFile("SKILL.md", DigestUtil.sha256Hex(bytes), bytes.length);
    }

    private SkillValidationReport report(List<SkillValidatedFile> files) {
        byte[] bytes = new byte[]{1, 2, 3};
        return new SkillValidationReport("a".repeat(64), true, null, null, "test-skill", "说明", "# 测试", bytes,
                DigestUtil.sha256Hex(bytes), "b".repeat(64), markdownFile().sizeBytes(), files);
    }
}
