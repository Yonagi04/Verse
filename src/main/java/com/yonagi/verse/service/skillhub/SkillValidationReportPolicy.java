package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** 在绑定制品前核对校验结果不变量；拒绝原因只映射固定安全文案。 */
@Component
public class SkillValidationReportPolicy {
    private static final int MAX_PACKAGE_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 1024;
    private static final int MAX_RELATIVE_PATH_LENGTH = 512;
    private static final int MAX_SEGMENT_LENGTH = 128;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern DEVICE = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?");
    private static final Map<String, String> REJECTIONS = Map.ofEntries(
            Map.entry("INVALID_ARCHIVE", "ZIP格式、条目大小或校验和不合法"),
            Map.entry("LIMIT_EXCEEDED", "ZIP超过资源上限"),
            Map.entry("UNSAFE_PATH", "ZIP包含越界或不兼容路径"),
            Map.entry("PATH_COLLISION", "ZIP包含重复或别名路径"),
            Map.entry("UNSUPPORTED_ENTRY", "ZIP包含链接、加密或不支持的文件类型"),
            Map.entry("EMPTY_ARCHIVE", "ZIP没有可发布文件"),
            Map.entry("SKILL_COUNT_INVALID", "每次必须且只能上传一个Skill"),
            Map.entry("INVALID_LAYOUT", "ZIP只支持根目录或单层包装目录"),
            Map.entry("INVALID_SKILL_MARKDOWN", "SKILL.md编码或YAML声明不合法"),
            Map.entry("SECRET_DETECTED", "包内疑似包含秘密或本机凭据，请清理后重新上传"),
            Map.entry("INVALID_TEXT", "文本资源包含非法空字节"),
            Map.entry("INVALID_RESOURCE_REFERENCE", "Markdown包含不安全或缺失的本地资源引用"));

    /**
     * @param report 包校验结果
     * @return 通过不变量验证的原结果；不修改文件字节
     */
    public SkillValidationReport validate(SkillValidationReport report) {
        if (report == null || !isHash(report.sourceSha256())) {
            throw invalid();
        }
        if (!report.success()) {
            if (!REJECTIONS.containsKey(report.code()) || report.archive() != null || report.skillMarkdown() != null
                    || report.files() == null || !report.files().isEmpty()) {
                throw invalid();
            }
            return report;
        }
        if (report.archive() == null || report.archive().length == 0
                || report.archive().length > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES
                || report.files() == null || report.files().isEmpty()
                || report.files().size() > SkillPackageLimitConstant.MAX_FILE_COUNT
                || report.unpackedBytes() <= 0 || report.unpackedBytes() > SkillPackageLimitConstant.MAX_UNPACKED_BYTES
                || report.skillMarkdown() == null || report.skillMarkdown().getBytes(StandardCharsets.UTF_8).length
                > SkillPackageLimitConstant.MAX_SKILL_MARKDOWN_BYTES
                || report.packageName() == null || report.packageName().length() > MAX_PACKAGE_NAME_LENGTH
                || !NAME.matcher(report.packageName()).matches()
                || report.packageDescription() == null || report.packageDescription().isBlank()
                || report.packageDescription().length() > MAX_DESCRIPTION_LENGTH
                || !isHash(report.archiveSha256()) || !isHash(report.contentSha256())
                || !report.archiveSha256().equals(DigestUtil.sha256Hex(report.archive()))) {
            throw invalid();
        }
        long total = 0L;
        Set<String> paths = new HashSet<>();
        boolean markdownFound = false;
        for (SkillValidatedFile file : report.files()) {
            if (file == null || file.relativePath() == null || file.relativePath().length() > MAX_RELATIVE_PATH_LENGTH
                    || file.relativePath().isEmpty() || !isHash(file.sha256()) || file.sizeBytes() < 0
                    || file.sizeBytes() > SkillPackageLimitConstant.MAX_UNPACKED_BYTES) {
                throw invalid();
            }
            validatePath(file.relativePath());
            if (!paths.add(file.relativePath().toLowerCase(Locale.ROOT))) {
                throw invalid();
            }
            if ("SKILL.md".equals(file.relativePath())) {
                byte[] markdown = report.skillMarkdown().getBytes(StandardCharsets.UTF_8);
                if (file.sizeBytes() != markdown.length || !file.sha256().equals(DigestUtil.sha256Hex(markdown))) {
                    throw invalid();
                }
                markdownFound = true;
            }
            total += file.sizeBytes();
        }
        if (total != report.unpackedBytes() || !markdownFound) {
            throw invalid();
        }
        return report;
    }

    /**
     * @param code 已由本策略接受的拒绝原因
     * @return 固定安全文案
     */
    public String reason(String code) {
        String reason = REJECTIONS.get(code);
        if (reason == null) {
            throw invalid();
        }
        return reason;
    }

    private boolean isHash(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private void validatePath(String path) {
        if (path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || !Normalizer.isNormalized(path, Normalizer.Form.NFC)) {
            throw invalid();
        }
        String[] segments = path.split("/", -1);
        if (segments.length > SkillPackageLimitConstant.MAX_DIRECTORY_DEPTH) {
            throw invalid();
        }
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.length() > MAX_SEGMENT_LENGTH
                    || segment.endsWith(".") || segment.endsWith(" ") || DEVICE.matcher(segment).matches()) {
                throw invalid();
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (Character.isISOControl(character) || Character.getType(character) == Character.FORMAT
                        || "<>\"|?*".indexOf(character) >= 0) {
                    throw invalid();
                }
            }
        }
    }

    private ServerException invalid() {
        return new ServerException("Skill校验结果不合法", SkillErrorCodeEnum.UNAVAILABLE);
    }
}
