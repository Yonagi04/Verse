package com.yonagi.verse.service.skillhub;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 扩展名白名单决定展示方式，实际内容检查只收窄支持范围，不自动猜测格式。 */
final class SkillFilePreviewPolicy {
    static final int MAX_PREVIEW_BYTES = 1024 * 1024;
    private static final Set<String> TEXT = Set.of("txt", "text", "log", "csv", "tsv");
    private static final Set<String> MARKDOWN = Set.of("md", "markdown", "mdown");
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("c", "c"), Map.entry("h", "cpp"), Map.entry("cc", "cpp"),
            Map.entry("cpp", "cpp"), Map.entry("cxx", "cpp"), Map.entry("hpp", "cpp"),
            Map.entry("hxx", "cpp"), Map.entry("cs", "csharp"), Map.entry("java", "java"),
            Map.entry("py", "python"), Map.entry("pyw", "python"), Map.entry("go", "go"),
            Map.entry("html", "xml"), Map.entry("htm", "xml"), Map.entry("xml", "xml"),
            Map.entry("css", "css"), Map.entry("scss", "scss"), Map.entry("less", "less"),
            Map.entry("js", "javascript"), Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
            Map.entry("jsx", "javascript"), Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("json", "json"), Map.entry("jsonc", "json"), Map.entry("yml", "yaml"),
            Map.entry("yaml", "yaml"), Map.entry("sh", "bash"), Map.entry("bash", "bash"),
            Map.entry("zsh", "bash"), Map.entry("sql", "sql"), Map.entry("rb", "ruby"),
            Map.entry("rs", "rust"), Map.entry("php", "php"), Map.entry("swift", "swift"),
            Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"), Map.entry("lua", "lua"),
            Map.entry("r", "r"), Map.entry("pl", "perl"), Map.entry("ini", "ini"),
            Map.entry("toml", "ini"), Map.entry("properties", "ini"), Map.entry("diff", "diff"),
            Map.entry("patch", "diff"), Map.entry("graphql", "graphql"), Map.entry("gql", "graphql"),
            Map.entry("m", "objectivec"), Map.entry("mm", "objectivec"), Map.entry("vb", "vbnet"));

    /** @return 仅按文件名选择展示方式；SVG、办公文档和未知后缀不会按文本放行 */
    Format format(String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (Set.of("makefile", "gnumakefile").contains(filename)) {
            return new Format("CODE", "makefile");
        }
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1);
        if (TEXT.contains(extension)) {
            return new Format("TEXT", null);
        }
        if (MARKDOWN.contains(extension)) {
            return new Format("MARKDOWN", "markdown");
        }
        String language = LANGUAGES.get(extension);
        return language == null ? new Format("UNSUPPORTED", null) : new Format("CODE", language);
    }

    /** @return 严格 UTF-8 文本；非法编码或二进制控制字符返回 null，空文本返回空字符串 */
    String text(byte[] bytes) {
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) && character != '\n' && character != '\r'
                    && character != '\t' && character != '\f') {
                return null;
            }
        }
        return value;
    }

    record Format(String kind, String language) {
    }
}
