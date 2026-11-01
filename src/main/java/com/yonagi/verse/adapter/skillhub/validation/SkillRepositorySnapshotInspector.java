package com.yonagi.verse.adapter.skillhub.validation;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import com.yonagi.verse.common.constant.SkillGithubLimitConstant;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillValidationIssueDTO;
import com.yonagi.verse.service.skillhub.port.SkillGithubSourceException;
import com.yonagi.verse.service.skillhub.port.SkillImportBudget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

/** 只扫描本地只读仓库ZIP，提取一个完整子树；从不执行或按仓库路径落盘。 */
@Component
@RequiredArgsConstructor
public class SkillRepositorySnapshotInspector implements com.yonagi.verse.service.skillhub.port.SkillRepositoryInspector {
    private static final int BUFFER_BYTES = 8192;
    private static final int MAX_NAME_BYTES = 4096;
    private static final String SKILL = "SKILL.md";
    private static final String LFS = "version https://git-lfs.github.com/spec/v1";
    private static final java.util.regex.Pattern GIT_SHA = java.util.regex.Pattern.compile("[0-9a-f]{40}");
    private final ObjectMapper mapper;
    private final SkillArchivePathPolicy paths = new SkillArchivePathPolicy(1152, 32);

    /** @return 初检候选；所有匹配都计数，不能过滤坏候选后自动选择另一份 */
    @Override
    public List<SkillGithubCandidateDTO> discover(Path archive, byte[] treeBytes, String directory,
                                                 String scanDigest, SkillImportBudget budget) {
        Map<String, TreeEntry> tree = tree(treeBytes, budget);
        List<String> roots = tree.values().stream().filter(entry -> entry.path().equals(SKILL)
                        || entry.path().endsWith("/" + SKILL)).map(entry -> parent(entry.path()))
                .filter(path -> within(path, directory)).sorted().toList();
        if (roots.isEmpty()) {
            throw rejected("NO_SKILL", "所选目录未找到SKILL.md", directory);
        }
        if (roots.size() > SkillGithubLimitConstant.MAX_CANDIDATES) {
            throw rejected("SOURCE_LIMIT_EXCEEDED", "匹配Skill超过100个，请缩小扫描目录", directory);
        }
        Snapshot snapshot = scan(archive, tree, null, Set.copyOf(roots), budget);
        List<SkillGithubCandidateDTO> candidates = new ArrayList<>();
        for (String root : roots) {
            budget.check();
            List<SkillValidationIssueDTO> issues = new ArrayList<>();
            List<TreeEntry> entries = tree.values().stream().filter(entry -> within(entry.path(), root))
                    .filter(entry -> !entry.mode().equals("040000")).toList();
            long bytes = entries.stream().mapToLong(TreeEntry::size).sum();
            String markdownPath = root.isEmpty() ? SKILL : root + "/" + SKILL;
            String name = null;
            String description = null;
            try {
                if (roots.stream().anyMatch(other -> !other.equals(root) && within(other, root))) {
                    throw new SkillPackageValidationException("NESTED_SKILL", "此目录包含其他Skill，请选择独立子目录");
                }
                checkEntries(entries, root);
                if (entries.size() > SkillPackageLimitConstant.MAX_FILE_COUNT
                        || bytes > SkillPackageLimitConstant.MAX_UNPACKED_BYTES) {
                    throw new SkillPackageValidationException("LIMIT_EXCEEDED", "所选Skill文件数或体积超过限制");
                }
                Preview preview = snapshot.markdowns().get(markdownPath);
                if (preview == null) {
                    throw new SkillPackageValidationException("SOURCE_INTEGRITY_FAILED", "固定提交的SKILL.md不在归档中");
                }
                if (preview.code() != null) {
                    throw new SkillPackageValidationException(preview.code(), preview.message());
                }
                name = preview.name();
                description = preview.description();
            } catch (SkillPackageValidationException exception) {
                issues.add(new SkillValidationIssueDTO(exception.getCode(), markdownPath, null, exception.getMessage()));
            }
            candidates.add(new SkillGithubCandidateDTO(DigestUtil.sha256Hex(scanDigest + "\n" + root), root,
                    name, description, entries.size(), bytes, issues.isEmpty(), List.copyOf(issues)));
        }
        return List.copyOf(candidates);
    }

    /** @return 固定tree中完整所选子树重新打包的单Skill输入；原仓库摘要不可被此摘要覆盖 */
    @Override
    public byte[] extract(Path archive, byte[] treeBytes, SkillGithubCandidateDTO candidate, SkillImportBudget budget) {
        Map<String, TreeEntry> tree = tree(treeBytes, budget);
        List<TreeEntry> selected = tree.values().stream().filter(entry -> within(entry.path(), candidate.directory()))
                .filter(entry -> !entry.mode().equals("040000")).toList();
        checkEntries(selected, candidate.directory());
        if (!Boolean.TRUE.equals(candidate.selectable())) {
            throw rejected("INVALID_SKILL_MARKDOWN", "候选未通过初检，请选择其他目录", candidate.directory());
        }
        Snapshot snapshot = scan(archive, tree, candidate.directory(), Set.of(), budget);
        String prefix = candidate.directory().isEmpty() ? "" : candidate.directory() + "/";
        Map<String, byte[]> relative = new TreeMap<>();
        for (TreeEntry entry : selected) {
            budget.check();
            byte[] bytes = snapshot.selected().get(entry.path());
            if (bytes == null || bytes.length != entry.size() || !blobHash(bytes).equals(entry.sha())) {
                throw rejected("SOURCE_INTEGRITY_FAILED", "固定提交文件缺失或与Git对象不一致", entry.path());
            }
            if (bytes.length >= LFS.length() && new String(bytes, 0, LFS.length(), StandardCharsets.US_ASCII).equals(LFS)) {
                throw rejected("UNSUPPORTED_GIT_ENTRY", "所选目录包含Git LFS指针，请提供完整资源ZIP", entry.path());
            }
            relative.put(entry.path().substring(prefix.length()), bytes);
        }
        if (relative.size() != snapshot.selected().size()) {
            throw rejected("SOURCE_INTEGRITY_FAILED", "归档包含固定tree之外的所选文件", candidate.directory());
        }
        String wrapper = candidate.directory().isEmpty() ? ""
                : candidate.directory().substring(candidate.directory().lastIndexOf('/') + 1) + "/";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(new java.io.FilterOutputStream(output) {
            private void check(int length) {
                budget.check();
                if ((long) output.size() + length > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES) {
                    throw rejected("LIMIT_EXCEEDED", "所选Skill压缩后超过20MiB", candidate.directory());
                }
            }
            @Override
            public void write(int value) throws IOException {
                check(1);
                out.write(value);
            }
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                check(length);
                out.write(bytes, offset, length);
            }
        }, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> file : relative.entrySet()) {
                budget.check();
                ZipEntry entry = new ZipEntry(wrapper + file.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                byte[] bytes = file.getValue();
                for (int index = 0; index < bytes.length; index += BUFFER_BYTES) {
                    budget.check();
                    zip.write(bytes, index, Math.min(BUFFER_BYTES, bytes.length - index));
                }
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw rejected("INVALID_ARCHIVE", "无法构造所选Skill快照", candidate.directory());
        }
        return output.toByteArray();
    }

    private Map<String, TreeEntry> tree(byte[] bytes, SkillImportBudget budget) {
        try {
            if (bytes == null || bytes.length > SkillGithubLimitConstant.MAX_TREE_BYTES) {
                throw rejected("SOURCE_LIMIT_EXCEEDED", "仓库目录超过限制", "");
            }
            JsonNode json = mapper.readTree(bytes);
            if (json == null || json.path("truncated").asBoolean(true) || !json.path("tree").isArray()
                    || json.path("tree").size() > SkillGithubLimitConstant.MAX_ENTRIES) {
                throw rejected("SOURCE_LIMIT_EXCEEDED", "仓库目录不完整或超过限制", "");
            }
            Map<String, TreeEntry> entries = new TreeMap<>();
            Set<String> aliases = new HashSet<>();
            for (JsonNode value : json.path("tree")) {
                budget.check();
                String path = paths.validate(value.path("path").asText(), false);
                String mode = value.path("mode").asText();
                String sha = value.path("sha").asText();
                long size = value.path("size").asLong(0);
                if (size < 0 || !GIT_SHA.matcher(sha).matches() || !aliases.add(paths.collisionKey(path))) {
                    throw rejected("SOURCE_INTEGRITY_FAILED", "仓库目录包含别名或不完整条目", path);
                }
                entries.put(path, new TreeEntry(path, mode, sha, size));
            }
            return entries;
        } catch (IOException | SkillPackageValidationException exception) {
            throw rejected("INVALID_ARCHIVE", "仓库目录结构不合法", "");
        }
    }

    private Snapshot scan(Path archive, Map<String, TreeEntry> tree, String selectedRoot,
                           Set<String> candidateRoots, SkillImportBudget budget) {
        Map<String, Preview> markdowns = new HashMap<>();
        Map<String, byte[]> selected = new HashMap<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        long selectedBytes = 0;
        String wrapper = null;
        try {
            centralDirectory(archive, budget);
            try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
                Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    budget.check();
                    ZipArchiveEntry entry = entries.nextElement();
                    String rawName = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(entry.getRawName())).toString();
                    String fullPath = paths.validate(rawName, entry.isDirectory());
                    if (!rawName.equals(entry.getName()) || !seen.add(paths.collisionKey(fullPath))) {
                        throw rejected("PATH_COLLISION", "仓库归档路径冲突", "");
                    }
                    int slash = fullPath.indexOf('/');
                    String currentWrapper = slash < 0 ? fullPath : fullPath.substring(0, slash);
                    if (wrapper == null) {
                        wrapper = currentWrapper;
                    }
                    if (!wrapper.equals(currentWrapper) || (slash < 0 && !entry.isDirectory())) {
                        throw rejected("INVALID_LAYOUT", "GitHub归档必须是单个仓库包装目录", "");
                    }
                    if (entry.isDirectory()) {
                        if (entry.getSize() != 0) {
                            throw rejected("UNSUPPORTED_GIT_ENTRY", "目录条目不能携带文件内容", "");
                        }
                        continue;
                    }
                    String path = fullPath.substring(slash + 1);
                    boolean keep = selectedRoot != null && within(path, selectedRoot);
                    TreeEntry expected = tree.get(path);
                    boolean markdown = candidateRoots.contains(parent(path))
                            && (path.equals(SKILL) || path.endsWith("/" + SKILL))
                            && expected != null && (expected.mode().equals("100644") || expected.mode().equals("100755"));
                    if (entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry)
                            || entry.getSize() < 0 || entry.getCrc() < 0) {
                        throw rejected("UNSUPPORTED_GIT_ENTRY", "仓库包含不支持的归档条目", path);
                    }
                    if (keep && (expected == null || !(expected.mode().equals("100644")
                            || expected.mode().equals("100755")) || entry.isUnixSymlink())) {
                        throw rejected("UNSUPPORTED_GIT_ENTRY", "所选Skill包含链接或非普通文件", path);
                    }
                    ByteArrayOutputStream bytes = (keep || markdown) ? new ByteArrayOutputStream() : null;
                    CRC32 crc = new CRC32();
                    long size = 0;
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] buffer = new byte[BUFFER_BYTES];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            budget.check();
                            total += count;
                            size += count;
                            if (keep) {
                                selectedBytes += count;
                            }
                            if (total > SkillGithubLimitConstant.MAX_UNPACKED_BYTES
                                    || selectedBytes > SkillPackageLimitConstant.MAX_UNPACKED_BYTES) {
                                throw rejected("SOURCE_LIMIT_EXCEEDED", "实际展开字节超过限制", path);
                            }
                            crc.update(buffer, 0, count);
                            if (bytes != null && (keep || size <= SkillPackageLimitConstant.MAX_SKILL_MARKDOWN_BYTES)) {
                                bytes.write(buffer, 0, count);
                            }
                        }
                    }
                    if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                        throw rejected("SOURCE_INTEGRITY_FAILED", "仓库条目大小或校验和不匹配", path);
                    }
                    if (markdown) {
                        markdowns.put(path, preview(bytes.toByteArray(), size, path, budget));
                    }
                    if (keep) {
                        selected.put(path, bytes.toByteArray());
                    }
                }
            }
        } catch (IOException | SkillPackageValidationException exception) {
            throw rejected("INVALID_ARCHIVE", "仓库归档结构不合法或已损坏", "");
        }
        return new Snapshot(markdowns, selected);
    }

    /** 在Commons Compress创建Entry集合前先核验真实中央目录计数与边界。 */
    private void centralDirectory(Path path, SkillImportBudget budget) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            if (length < 22 || length > SkillGithubLimitConstant.MAX_ARCHIVE_BYTES) {
                throw rejected("SOURCE_LIMIT_EXCEEDED", "仓库归档大小不合法", "");
            }
            byte[] tail = new byte[(int) Math.min(length, 65557)];
            file.seek(length - tail.length);
            file.readFully(tail);
            ByteBuffer data = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            int end = -1;
            for (int index = tail.length - 22; index >= 0; index--) {
                budget.check();
                if (data.getInt(index) == 0x06054b50) {
                    end = index;
                    break;
                }
            }
            if (end < 0 || end + 22 + Short.toUnsignedInt(data.getShort(end + 20)) != tail.length
                    || data.getShort(end + 4) != 0 || data.getShort(end + 6) != 0) {
                throw rejected("INVALID_ARCHIVE", "仓库ZIP中央目录不合法", "");
            }
            int count = Short.toUnsignedInt(data.getShort(end + 10));
            long size = Integer.toUnsignedLong(data.getInt(end + 12));
            long offset = Integer.toUnsignedLong(data.getInt(end + 16));
            long directoryEnd = length - tail.length + end;
            if (count > SkillGithubLimitConstant.MAX_ENTRIES || count != Short.toUnsignedInt(data.getShort(end + 8))
                    || offset > directoryEnd || size != directoryEnd - offset) {
                throw rejected("SOURCE_LIMIT_EXCEEDED", "仓库ZIP中央目录超过限制", "");
            }
            file.seek(offset);
            int actual = 0;
            byte[] header = new byte[46];
            while (file.getFilePointer() < directoryEnd) {
                budget.check();
                if (++actual > SkillGithubLimitConstant.MAX_ENTRIES || directoryEnd - file.getFilePointer() < 46) {
                    throw rejected("SOURCE_LIMIT_EXCEEDED", "仓库ZIP条目超过限制", "");
                }
                file.readFully(header);
                ByteBuffer entry = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                int name = Short.toUnsignedInt(entry.getShort(28));
                long skip = (long) name + Short.toUnsignedInt(entry.getShort(30))
                        + Short.toUnsignedInt(entry.getShort(32));
                if (entry.getInt(0) != 0x02014b50 || name > MAX_NAME_BYTES
                        || skip > directoryEnd - file.getFilePointer()) {
                    throw rejected("INVALID_ARCHIVE", "仓库ZIP中央目录条目不合法", "");
                }
                file.seek(file.getFilePointer() + skip);
            }
            if (actual != count) {
                throw rejected("INVALID_ARCHIVE", "仓库ZIP声明计数与实际条目不符", "");
            }
        }
    }

    private void checkEntries(List<TreeEntry> entries, String root) {
        for (TreeEntry entry : entries) {
            if (!(entry.mode().equals("100644") || entry.mode().equals("100755"))) {
                throw new SkillPackageValidationException("UNSUPPORTED_GIT_ENTRY", "所选Skill含符号链接或子模块");
            }
            String relative = root.isEmpty() ? entry.path() : entry.path().substring(root.length() + 1);
            new SkillArchivePathPolicy(512, 10).validate(relative, false);
        }
    }

    private Preview preview(byte[] bytes, long actualSize, String path, SkillImportBudget budget) {
        if (actualSize > SkillPackageLimitConstant.MAX_SKILL_MARKDOWN_BYTES) {
            return new Preview(null, null, "LIMIT_EXCEEDED", "SKILL.md超过1MiB限制");
        }
        try {
            String directory = parent(path);
            SkillPackageDeclaration declaration = new SkillMarkdownPolicy(control(budget)).parse(bytes,
                    directory.isEmpty() ? null : directory.substring(directory.lastIndexOf('/') + 1));
            return new Preview(declaration.name(), declaration.description().substring(0,
                    Math.min(300, declaration.description().length())), null, null);
        } catch (SkillPackageValidationException exception) {
            return new Preview(null, null, exception.getCode(), exception.getMessage());
        }
    }

    private SkillValidationControl control(SkillImportBudget budget) {
        return new SkillValidationControl(() -> {
            budget.check();
            return true;
        });
    }

    private boolean within(String path, String root) {
        return root.isEmpty() || path.equals(root) || path.startsWith(root + "/");
    }

    private String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private String blobHash(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java运行时缺少Git对象摘要算法", exception);
        }
    }

    private SkillGithubSourceException rejected(String code, String message, String path) {
        return new SkillGithubSourceException(code, message, false, null,
                List.of(new SkillValidationIssueDTO(code, path.substring(0, Math.min(path.length(), 512)), null, message)));
    }

    private record TreeEntry(String path, String mode, String sha, long size) { }
    private record Preview(String name, String description, String code, String message) { }
    private record Snapshot(Map<String, Preview> markdowns, Map<String, byte[]> selected) { }
}
