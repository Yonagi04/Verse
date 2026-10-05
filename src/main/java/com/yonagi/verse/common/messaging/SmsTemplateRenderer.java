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
import java.util.regex.Pattern;

/** 静态模板启动加载；不使用表达式引擎，避免模板获得代码执行能力。 */
public final class SmsTemplateRenderer {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]{0,63})}");
    private final Map<String, Template> templates;

    public SmsTemplateRenderer(Resource resource) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(262144);
        try (InputStream stream = resource.getInputStream()) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(stream);
            if (!(loaded instanceof Map<?, ?> root) || root.size() != 1
                    || !(root.get("templates") instanceof Map<?, ?> entries) || entries.isEmpty()) {
                throw new IllegalArgumentException("Expected nonempty templates map");
            }
            Map<String, Template> parsed = new HashMap<>();
            entries.forEach((code, value) -> {
                if (!(code instanceof String id) || !id.matches("[A-Za-z0-9_-]{1,64}")
                        || !(value instanceof Map<?, ?> entry) || entry.size() != 1
                        || !(entry.get("content") instanceof String content) || content.isBlank()
                        || content.length() > 2000 || content.contains("【") || content.contains("】")
                        || content.contains("\r") || content.contains("\n")) {
                    throw new IllegalArgumentException("Invalid template entry");
                }
                Set<String> variables = new HashSet<>();
                var matcher = VARIABLE.matcher(content);
                while (matcher.find()) variables.add(matcher.group(1));
                if (VARIABLE.matcher(content).replaceAll("").contains("${")) {
                    throw new IllegalArgumentException("Invalid placeholder");
                }
                parsed.put(id, new Template(content, Set.copyOf(variables)));
            });
            templates = Map.copyOf(parsed);
        } catch (Exception e) {
            // 不附加 YAML 原文或异常消息，模板中可能误放秘密。
            throw new IllegalStateException("Invalid SMS template resource");
        }
    }

    public String render(String signName, String templateCode, Map<String, String> params) {
        Template template = templates.get(templateCode);
        MessageRequestValidation.require(template != null && params != null && template.variables().equals(params.keySet()));
        MessageRequestValidation.require(MessageRequestValidation.text(signName, 50) && !signName.contains("【") && !signName.contains("】"));
        var matcher = VARIABLE.matcher(template.content());
        String rendered = matcher.replaceAll(match -> {
            String value = params.get(match.group(1));
            MessageRequestValidation.require(MessageRequestValidation.text(value, 256) && !value.contains("${"));
            return java.util.regex.Matcher.quoteReplacement(value);
        });
        MessageRequestValidation.require(rendered.length() <= 2000);
        return "【" + signName + "】" + rendered;
    }

    private record Template(String content, Set<String> variables) { }
}
