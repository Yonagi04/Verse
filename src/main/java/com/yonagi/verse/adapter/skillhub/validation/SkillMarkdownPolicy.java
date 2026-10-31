package com.yonagi.verse.adapter.skillhub.validation;

import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UTF-8与Agent Skills frontmatter检查，不渲染、不执行Markdown或脚本。 */
final class SkillMarkdownPolicy {
    private static final int MAX_MARKDOWN_BYTES = SkillPackageLimitConstant.MAX_SKILL_MARKDOWN_BYTES;
    private static final int MAX_FRONTMATTER_BYTES = SkillPackageLimitConstant.MAX_FRONTMATTER_BYTES;
    private static final int MAX_DESCRIPTION = 1024;
    private static final int MAX_COMPATIBILITY = 500;
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\r?\\n(.*?)\\r?\\n---(?:\\r?\\n|\\z)",
            Pattern.DOTALL);
    private static final Set<String> FIELDS = Set.of("name", "description", "license", "compatibility", "metadata",
            "allowed-tools");

    private final SkillValidationControl control;

    SkillMarkdownPolicy(SkillValidationControl control) {
        this.control = control;
    }

    SkillPackageDeclaration parse(byte[] bytes, String directoryName) {
        control.check();
        if (bytes.length > MAX_MARKDOWN_BYTES) {
            throw invalid("SKILL.md超过大小限制");
        }
        String markdown = decode(bytes);
        Matcher frontmatter = FRONTMATTER.matcher(control.watching(markdown));
        if (!frontmatter.find()
                || frontmatter.group(1).getBytes(StandardCharsets.UTF_8).length > MAX_FRONTMATTER_BYTES) {
            throw invalid("SKILL.md需要有界的YAML frontmatter");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(MAX_FRONTMATTER_BYTES);
        Object parsed;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(options));
            for (Event event : yaml.parse(control.reader(frontmatter.group(1)))) {
                if (event instanceof AliasEvent || (event instanceof NodeEvent node && node.getAnchor() != null)) {
                    throw invalid("YAML不接受锚点或别名");
                }
            }
            parsed = yaml.load(control.reader(frontmatter.group(1)));
        } catch (YAMLException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SkillValidationCancelledException cancelled) {
                    throw cancelled;
                }
                if (cause == cause.getCause()) {
                    break;
                }
            }
            control.check();
            throw invalid("YAML包含非法结构、重复键或不安全类型");
        }
        control.check();
        if (!(parsed instanceof Map<?, ?> fields) || !FIELDS.containsAll(fields.keySet())) {
            throw invalid("frontmatter字段不合法");
        }
        String name = requiredString(fields, "name", 64);
        String description = requiredString(fields, "description", MAX_DESCRIPTION);
        if (!NAME.matcher(name).matches() || (directoryName != null && !directoryName.equals(name))) {
            throw invalid("包名称须使用小写字母、数字及单个连字符，并与包装目录一致");
        }
        optionalString(fields, "license", MAX_DESCRIPTION);
        optionalString(fields, "compatibility", MAX_COMPATIBILITY);
        optionalAllowedTools(fields);
        Object metadata = fields.get("metadata");
        if (metadata != null && (!(metadata instanceof Map<?, ?> entries)
                || entries.entrySet().stream().anyMatch(entry -> !(entry.getKey() instanceof String)
                        || !(entry.getValue() instanceof String)))) {
            throw invalid("metadata必须为字符串键值表");
        }
        return new SkillPackageDeclaration(name, description, markdown);
    }

    String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            for (int index = 0; index < text.length(); index++) {
                control.accessed();
                char character = text.charAt(index);
                if (Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t') {
                    throw invalid("Markdown包含不可见控制字符");
                }
            }
            return text;
        } catch (CharacterCodingException exception) {
            throw invalid("SKILL.md须使用有效UTF-8编码");
        }
    }

    void validateResourceEncoding(byte[] bytes) {
        // 资源可以达到50MiB；流式严格解码，避免创建与全文等长的临时char数组。
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                control.check();
                for (int index = 0; index < count; index++) {
                    char character = buffer[index];
                    if (Character.isISOControl(character) && character != '\n'
                            && character != '\r' && character != '\t') {
                        throw invalid("Markdown包含不可见控制字符");
                    }
                }
            }
        } catch (IOException exception) {
            throw invalid("Markdown须使用有效UTF-8编码");
        }
    }

    private String requiredString(Map<?, ?> fields, String field, int maximum) {
        Object value = fields.get(field);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum) {
            throw invalid("frontmatter必填字段类型或长度不合法");
        }
        return text;
    }

    private void optionalString(Map<?, ?> fields, String field, int maximum) {
        if (fields.containsKey(field)) {
            requiredString(fields, field, maximum);
        }
    }

    private void optionalAllowedTools(Map<?, ?> fields) {
        Object value = fields.get("allowed-tools");
        if (!(value instanceof List<?> tools)) {
            optionalString(fields, "allowed-tools", MAX_FRONTMATTER_BYTES);
            return;
        }
        // 兼容Claude Code列表声明；仅检查类型，原始Markdown继续作为包内容和摘要的权威。
        if (tools.isEmpty()) {
            throw invalid("allowed-tools列表不能为空");
        }
        for (Object tool : tools) {
            if (!(tool instanceof String text) || text.isBlank()) {
                throw invalid("allowed-tools列表项必须为非空字符串");
            }
        }
    }

    private SkillPackageValidationException invalid(String reason) {
        return new SkillPackageValidationException("INVALID_SKILL_MARKDOWN", reason);
    }
}
