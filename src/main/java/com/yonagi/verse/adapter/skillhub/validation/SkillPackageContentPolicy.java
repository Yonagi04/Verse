package com.yonagi.verse.adapter.skillhub.validation;

import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅静态检查秘密与本地资源引用；不执行代码、不访问网络。 */
final class SkillPackageContentPolicy {
    private static final int SCAN_CHUNK_BYTES = 64 * 1024;
    private static final int SCAN_OVERLAP_BYTES = 128;
    private static final int MAX_REFERENCE_BYTES = 8192;
    private static final Pattern SECRET = Pattern.compile(
            "-----BEGIN (?:[A-Z0-9 ]{0,64}PRIVATE KEY|PGP PRIVATE KEY BLOCK)-----|AKIA[0-9A-Z]{16}"
                    + "|(?:ghp_|github_pat_)[A-Za-z0-9_]{20,}|sk-(?:proj-)?[A-Za-z0-9_-]{32,}");
    private static final Pattern INLINE_LINK = Pattern.compile("!?\\[[^]\\r\\n]*]\\(<?([^\\s)>]+)>?(?:[^)]*)\\)");
    // 字节视图只允许ASCII CR/LF作为行边界，避免UTF-8续字节0x85被多行锚点视为NEL。
    private static final Pattern REFERENCE_LINK = Pattern.compile(
            "(?:\\A|(?<=\\r)|(?<=\\n)) {0,3}\\[[^]\\r\\n]+]:\\s*<?([^\\s>]+)>?");
    private static final Pattern CODE_FENCE = Pattern.compile(
            "(?:\\A|(?<=\\r)|(?<=\\n)) {0,3}(`{3,}|~{3,})([^\\r\\n]*)(?:\\r\\n|\\r|\\n|\\z)");
    private static final Set<String> TEXT_SUFFIXES = Set.of("md", "txt", "json", "yaml", "yml", "py", "js", "ts",
            "sh", "toml", "ini", "cfg", "conf", "xml", "csv", "env");

    private final SkillValidationControl control;

    SkillPackageContentPolicy(SkillValidationControl control) {
        this.control = control;
    }

    void check(Map<String, byte[]> files, SkillMarkdownPolicy markdownPolicy) {
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            control.check();
            String lower = file.getKey().toLowerCase(Locale.ROOT);
            for (String segment : lower.split("/")) {
                if (segment.equals(".git") || segment.equals(".ssh") || segment.equals(".aws")
                        || segment.equals(".env") || segment.startsWith(".env.") || segment.equals("id_rsa")
                        || segment.equals("id_ed25519") || segment.equals(".npmrc")) {
                    throw secret();
                }
            }
            scanSecrets(file.getValue());
            if (lower.endsWith(".md")) {
                markdownPolicy.validateResourceEncoding(file.getValue());
                CharSequence markdown = control.watching(new MarkdownBytes(file.getValue(), 0, file.getValue().length));
                checkMarkdownLinks(markdown, file.getKey(), files);
            }
            int dot = lower.lastIndexOf('.');
            if (dot >= 0 && TEXT_SUFFIXES.contains(lower.substring(dot + 1))) {
                for (byte value : file.getValue()) {
                    control.accessed();
                    if (value == 0) {
                        throw new SkillPackageValidationException("INVALID_TEXT", "文本资源包含非法空字节");
                    }
                }
            }
        }
    }

    private void scanSecrets(byte[] bytes) {
        // 50MiB文本不能再保留完整ISO字符串副本；重叠覆盖所有受支持秘密前缀与首段检测长度。
        for (int position = 0; position < bytes.length; position += SCAN_CHUNK_BYTES) {
            control.check();
            int start = Math.max(0, position - SCAN_OVERLAP_BYTES);
            int end = Math.min(bytes.length, position + SCAN_CHUNK_BYTES);
            String content = new String(bytes, start, end - start, StandardCharsets.ISO_8859_1);
            if (SECRET.matcher(control.watching(content)).find()) {
                throw secret();
            }
        }
    }

    private void checkMarkdownLinks(CharSequence markdown, String source, Map<String, byte[]> files) {
        Matcher fences = CODE_FENCE.matcher(markdown);
        char openMarker = 0;
        int openLength = 0;
        int proseStart = 0;
        // 按区间扫描正文，避免复制大资源或把代码示例中的泛型、lambda误判为链接。
        while (fences.find()) {
            char marker = markdown.charAt(fences.start(1));
            int length = fences.end(1) - fences.start(1);
            if (openMarker == 0) {
                if (marker == '`' && containsBacktick(markdown, fences.start(2), fences.end(2))) {
                    continue;
                }
                checkProseLinks(markdown, proseStart, fences.start(), source, files);
                openMarker = marker;
                openLength = length;
            } else if (marker == openMarker && length >= openLength
                    && isClosingFenceTail(markdown, fences.start(2), fences.end(2))) {
                openMarker = 0;
                proseStart = fences.end();
            }
        }
        if (openMarker == 0) {
            checkProseLinks(markdown, proseStart, markdown.length(), source, files);
        }
    }

    private boolean containsBacktick(CharSequence markdown, int start, int end) {
        for (int index = start; index < end; index++) {
            if (markdown.charAt(index) == '`') {
                return true;
            }
        }
        return false;
    }

    private boolean isClosingFenceTail(CharSequence markdown, int start, int end) {
        for (int index = start; index < end; index++) {
            char character = markdown.charAt(index);
            if (character != ' ' && character != '\t') {
                return false;
            }
        }
        return true;
    }

    private void checkProseLinks(CharSequence markdown, int start, int end, String source, Map<String, byte[]> files) {
        checkLinks(INLINE_LINK.matcher(markdown).region(start, end), source, files);
        checkLinks(REFERENCE_LINK.matcher(markdown).region(start, end), source, files);
    }

    private void checkLinks(Matcher matcher, String source, Map<String, byte[]> files) {
        while (matcher.find()) {
            control.check();
            if (matcher.end(1) - matcher.start(1) > MAX_REFERENCE_BYTES) {
                throw reference();
            }
            String destination = matcher.group(1);
            if (destination.startsWith("#")) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(destination);
            } catch (IllegalArgumentException exception) {
                throw reference();
            }
            if (uri.getScheme() != null) {
                if (!Set.of("https", "http", "mailto").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                    throw reference();
                }
                continue;
            }
            String path = uri.getPath();
            if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0
                    || path.indexOf('\0') >= 0 || uri.getRawAuthority() != null) {
                throw reference();
            }
            Deque<String> segments = new ArrayDeque<>();
            int slash = source.lastIndexOf('/');
            if (slash >= 0) {
                for (String segment : source.substring(0, slash).split("/")) {
                    segments.addLast(segment);
                }
            }
            for (String segment : path.split("/", -1)) {
                if (segment.equals("..")) {
                    if (segments.isEmpty()) {
                        throw reference();
                    }
                    segments.removeLast();
                } else if (!segment.equals(".") && !segment.isEmpty()) {
                    segments.addLast(segment);
                }
            }
            String target = String.join("/", segments);
            if (!files.containsKey(target) && files.keySet().stream().noneMatch(key -> key.startsWith(target + "/"))) {
                throw reference();
            }
        }
    }

    /** Markdown链接语法使用ASCII分隔符；只在提取链接目标时解码UTF-8，不复制整份资源。 */
    private record MarkdownBytes(byte[] bytes, int offset, int length) implements CharSequence {
        @Override
        public char charAt(int index) {
            java.util.Objects.checkIndex(index, length);
            return (char) Byte.toUnsignedInt(bytes[offset + index]);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            java.util.Objects.checkFromToIndex(start, end, length);
            return new MarkdownBytes(bytes, offset + start, end - start);
        }

        @Override
        public String toString() {
            return new String(bytes, offset, length, StandardCharsets.UTF_8);
        }
    }

    private SkillPackageValidationException secret() {
        return new SkillPackageValidationException("SECRET_DETECTED", "包内疑似包含秘密或本机凭据，请清理后重新上传");
    }

    private SkillPackageValidationException reference() {
        return new SkillPackageValidationException("INVALID_RESOURCE_REFERENCE", "Markdown包含不安全或缺失的本地资源引用");
    }
}
