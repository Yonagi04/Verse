package com.yonagi.verse.common.messaging;

import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 启动加载不可变文字邮件模板；只替换占位符，不解释表达式或 HTML。 */
public final class EmailTemplateRenderer {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]{0,63})}");
    private static final Pattern TEMPLATE_CODE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_RESOURCE_CODE_POINTS = 262144;
    private static final int MAX_SUBJECT_LENGTH = 256;
    private static final int MAX_PARAMETER_LENGTH = 2048;
    private final Map<String, Template> templates;

    public EmailTemplateRenderer(Resource resource) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_RESOURCE_CODE_POINTS);
        try (InputStream stream = resource.getInputStream()) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(stream);
            if (!(loaded instanceof Map<?, ?> root) || root.size() != 1
                    || !(root.get("templates") instanceof Map<?, ?> entries) || entries.isEmpty()) {
                throw new IllegalArgumentException("Expected nonempty templates map");
            }
            Map<String, Template> parsed = new HashMap<>();
            entries.forEach((code, value) -> {
                if (!(code instanceof String id) || !TEMPLATE_CODE.matcher(id).matches()
                        || !(value instanceof Map<?, ?> entry) || entry.size() != 2
                        || !(entry.get("subject") instanceof String subject)
                        || !MessageRequestValidation.text(subject, MAX_SUBJECT_LENGTH)
                        || !(entry.get("content") instanceof String content) || content.isBlank()) {
                    throw new IllegalArgumentException("Invalid email template entry");
                }
                MessageRequestValidation.body(content);
                Set<String> variables = new HashSet<>();
                for (String text : new String[]{subject, content}) {
                    Matcher matcher = VARIABLE.matcher(text);
                    while (matcher.find()) {
                        variables.add(matcher.group(1));
                    }
                    if (VARIABLE.matcher(text).replaceAll("").contains("${")) {
                        throw new IllegalArgumentException("Invalid placeholder");
                    }
                }
                parsed.put(id, new Template(subject, content, Set.copyOf(variables)));
            });
            templates = Map.copyOf(parsed);
        } catch (Exception failure) {
            // 不附带 YAML 内容或解析异常消息，避免误配置的秘密进入日志。
            throw new IllegalStateException("Invalid email template resource");
        }
    }

    /** 参数集必须与标题和正文的占位符并集一致，值不能注入邮件头或递归占位符。 */
    public RenderedEmail render(String templateCode, Map<String, String> params) {
        MessageRequestValidation.require(templateCode != null);
        Template template = templates.get(templateCode);
        MessageRequestValidation.require(template != null && params != null
                && template.variables().equals(params.keySet()));
        for (String value : params.values()) {
            MessageRequestValidation.require(MessageRequestValidation.text(value, MAX_PARAMETER_LENGTH)
                    && !value.contains("${"));
        }
        String subject = substitute(template.subject(), params);
        String content = substitute(template.content(), params);
        MessageRequestValidation.require(MessageRequestValidation.text(subject, MAX_SUBJECT_LENGTH));
        MessageRequestValidation.body(content);
        return new RenderedEmail(subject, content);
    }

    private String substitute(String text, Map<String, String> params) {
        return VARIABLE.matcher(text).replaceAll(match -> Matcher.quoteReplacement(params.get(match.group(1))));
    }

    /** 完整渲染结果进入既有邮件任务快照，不存储模板内部结构。 */
    public record RenderedEmail(String subject, String content) { }

    private record Template(String subject, String content, Set<String> variables) { }
}
