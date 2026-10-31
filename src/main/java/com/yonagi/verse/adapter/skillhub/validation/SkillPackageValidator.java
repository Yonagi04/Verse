package com.yonagi.verse.adapter.skillhub.validation;

import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 单Skill有界安全解析与确定性规范化；不把任何上传路径写入文件系统。 */
public class SkillPackageValidator {
    static final int MAX_ARCHIVE_BYTES = (int) SkillPackageLimitConstant.MAX_ARCHIVE_BYTES;
    private static final String SKILL_MARKDOWN = "SKILL.md";
    private static final LocalDateTime ZIP_TIMESTAMP = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final int COMPRESSION_LEVEL = 6;
    private static final int BUFFER_BYTES = 8192;
    private static final int MAX_RELATIVE_PATH_LENGTH = 512;
    private static final int MAX_RELATIVE_DEPTH = SkillPackageLimitConstant.MAX_DIRECTORY_DEPTH;

    /**
     * @param archive 原ZIP字节，最多20MiB
     * @return 保留原文件字节的规范化包、校验快照与清单
     * @throws IOException ZIP结构不能解析
     * @throws SkillPackageValidationException 包不满足发布约束
     */
    public SkillValidationReport validate(byte[] archive) throws IOException {
        return validate(archive, new SkillValidationControl(() -> true));
    }

    SkillValidationReport validate(byte[] archive, SkillValidationControl control) throws IOException {
        control.check();
        if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) {
            throw new SkillPackageValidationException("LIMIT_EXCEEDED", "ZIP为空或超过20MiB限制");
        }
        Map<String, byte[]> files = new SkillArchiveReader().read(archive, control);
        List<String> candidates = files.keySet().stream()
                .filter(path -> path.equals(SKILL_MARKDOWN) || path.endsWith("/" + SKILL_MARKDOWN)).toList();
        if (candidates.size() != 1) {
            throw new SkillPackageValidationException("SKILL_COUNT_INVALID", "每次必须上传且只能上传一个Skill");
        }
        String candidate = candidates.getFirst();
        String prefix = candidate.substring(0, candidate.length() - SKILL_MARKDOWN.length());
        if (!prefix.isEmpty() && prefix.indexOf('/') != prefix.length() - 1) {
            throw new SkillPackageValidationException("INVALID_LAYOUT", "ZIP只支持根目录或单层包装目录");
        }
        Map<String, byte[]> relative = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            control.check();
            if (!file.getKey().startsWith(prefix)) {
                throw new SkillPackageValidationException("INVALID_LAYOUT", "包装目录外存在文件，请只上传单个完整Skill");
            }
            String relativePath = file.getKey().substring(prefix.length());
            if (relativePath.length() > MAX_RELATIVE_PATH_LENGTH
                    || relativePath.split("/").length > MAX_RELATIVE_DEPTH) {
                throw new SkillPackageValidationException("UNSAFE_PATH", "包内路径超过长度或目录深度限制");
            }
            relative.put(relativePath, file.getValue());
        }
        SkillMarkdownPolicy markdownPolicy = new SkillMarkdownPolicy(control);
        SkillPackageDeclaration declaration = markdownPolicy.parse(relative.get(SKILL_MARKDOWN),
                prefix.isEmpty() ? null : prefix.substring(0, prefix.length() - 1));
        new SkillPackageContentPolicy(control).check(relative, markdownPolicy);
        List<SkillValidatedFile> manifest = new ArrayList<>(relative.size());
        MessageDigest digest = sha256();
        long unpacked = 0L;
        for (Map.Entry<String, byte[]> file : relative.entrySet()) {
            control.check();
            byte[] path = file.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] content = file.getValue();
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
            digest.update(path);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
            for (int offset = 0; offset < content.length; offset += BUFFER_BYTES) {
                control.check();
                digest.update(content, offset, Math.min(BUFFER_BYTES, content.length - offset));
            }
            manifest.add(new SkillValidatedFile(file.getKey(), hash(content, control), content.length));
            unpacked += content.length;
        }
        byte[] normalized = normalize(declaration.name(), relative, control);
        return new SkillValidationReport(hash(archive, control), true, null, null,
                declaration.name(), declaration.description(),
                declaration.markdown(), normalized, hash(normalized, control), HexFormat.of().formatHex(digest.digest()),
                unpacked, List.copyOf(manifest));
    }

    private byte[] normalize(String name, Map<String, byte[]> files, SkillValidationControl control) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream limited = new OutputStream() {
            @Override
            public void write(int value) {
                check(1);
                bytes.write(value);
            }

            @Override
            public void write(byte[] data, int offset, int length) {
                check(length);
                bytes.write(data, offset, length);
            }

            private void check(int length) {
                control.check();
                if ((long) bytes.size() + length > MAX_ARCHIVE_BYTES) {
                    throw new SkillPackageValidationException("LIMIT_EXCEEDED", "规范化包超过20MiB限制");
                }
            }
        };
        try (ZipOutputStream output = new ZipOutputStream(limited, StandardCharsets.UTF_8)) {
            output.setLevel(COMPRESSION_LEVEL);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
            control.check();
                ZipEntry entry = new ZipEntry(name + "/" + file.getKey());
                entry.setTimeLocal(ZIP_TIMESTAMP);
                output.putNextEntry(entry);
                byte[] content = file.getValue();
                for (int offset = 0; offset < content.length; offset += BUFFER_BYTES) {
                    control.check();
                    output.write(content, offset, Math.min(BUFFER_BYTES, content.length - offset));
                }
                output.closeEntry();
            }
        }
        control.check();
        return bytes.toByteArray();
    }

    static String hash(byte[] content) {
        return hash(content, new SkillValidationControl(() -> true));
    }

    static String hash(byte[] content, SkillValidationControl control) {
        MessageDigest digest = sha256();
        for (int offset = 0; offset < content.length; offset += BUFFER_BYTES) {
            control.check();
            digest.update(content, offset, Math.min(BUFFER_BYTES, content.length - offset));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java运行时缺少SHA-256", exception);
        }
    }
}
