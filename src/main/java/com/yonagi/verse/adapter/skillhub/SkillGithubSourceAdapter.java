package com.yonagi.verse.adapter.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.constant.SkillGithubLimitConstant;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import com.yonagi.verse.service.skillhub.SkillGithubSourcePolicy;
import com.yonagi.verse.service.skillhub.SkillGithubSourceRequest;
import com.yonagi.verse.service.skillhub.port.SkillGithubSourceException;
import com.yonagi.verse.service.skillhub.port.SkillGithubSourcePort;
import com.yonagi.verse.service.skillhub.port.SkillImportBudget;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** 无凭据公开获取；仅允许固定API和一次受检归档跳转，不使用任何OAuth Token。 */
@Component
public class SkillGithubSourceAdapter implements SkillGithubSourcePort {
    private static final int JSON_LIMIT = 1024 * 1024;
    private static final int BUFFER_BYTES = 8192;
    private final ObjectMapper mapper;
    private final SkillGithubSourcePolicy policy;
    private final ThreadPoolTaskScheduler scheduler;
    private final CloseableHttpClient client;

    public SkillGithubSourceAdapter(ObjectMapper mapper, SkillGithubSourcePolicy policy,
                                    @Qualifier("skillLeaseScheduler") ThreadPoolTaskScheduler scheduler) {
        this.mapper = mapper;
        this.policy = policy;
        this.scheduler = scheduler;
        this.client = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(2).setMaxConnPerRoute(2).setDnsResolver(new SkillGithubDnsResolver())
                        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3))
                                .setSocketTimeout(Timeout.ofSeconds(10)).build()).build()).build();
    }

    @Override
    public List<SkillGithubSourceOptionDTO> resolve(SkillGithubSourceRequest request, SkillImportBudget budget) {
        String root = root(request.owner(), request.repository());
        JsonNode repository = json(root, JSON_LIMIT, budget, false);
        if (repository.path("private").asBoolean(true) || !repository.path("id").canConvertToLong()) {
            throw unavailable();
        }
        String repositoryId = repository.path("id").asText();
        String defaultBranch = repository.path("default_branch").asText();
        Map<String, SkillGithubSourceOptionDTO> options = new LinkedHashMap<>();
        for (SkillGithubSourceRequest split : policy.splits(request)) {
            String ref = "DEFAULT".equals(split.refType()) ? defaultBranch : split.ref();
            if (ref == null || ref.isBlank()) {
                throw rejected("REF_NOT_FOUND", "仓库没有可用默认分支或引用");
            }
            List<String> types = "AUTO".equals(split.refType()) ? List.of("BRANCH", "TAG") : List.of(split.refType());
            for (String type : types) {
                budget.check();
                String qualified = switch (type) {
                    case "DEFAULT", "BRANCH" -> "heads/" + ref;
                    case "TAG" -> "tags/" + ref;
                    case "COMMIT" -> ref;
                    default -> throw rejected("REF_NOT_FOUND", "不支持的仓库引用");
                };
                JsonNode commit = json(root + "/commits/" + segment(qualified), JSON_LIMIT, budget, true);
                if (commit == null) {
                    continue;
                }
                String sha = commit.path("sha").asText();
                String treeSha = commit.path("commit").path("tree").path("sha").asText();
                if (!policy.fullSha(sha) || !policy.fullSha(treeSha)
                        || ("COMMIT".equals(type) && !sha.equalsIgnoreCase(ref))) {
                    throw rejected("SOURCE_INTEGRITY_FAILED", "GitHub返回的提交信息不完整");
                }
                if (!split.directory().isEmpty() && !directoryExists(root, treeSha, split.directory(), budget)) {
                    continue;
                }
                SkillSourceDTO source = new SkillSourceDTO(1, "GITHUB", request.repositoryUrl(), repositoryId,
                        ref, type, sha.toLowerCase(java.util.Locale.ROOT), split.directory());
                String id = DigestUtil.sha256Hex(type + "\n" + ref + "\n" + sha + "\n" + split.directory());
                options.putIfAbsent(id, new SkillGithubSourceOptionDTO(id, source, treeSha));
            }
        }
        if (options.isEmpty()) {
            throw rejected("REF_NOT_FOUND", "未找到匹配的公开引用与目录，请填写明确的分支或Tag");
        }
        if (options.size() > 20) {
            throw rejected("SOURCE_AMBIGUOUS", "来源切分过多，请填写明确的引用与目录");
        }
        return List.copyOf(options.values());
    }

    @Override
    public byte[] tree(SkillGithubSourceOptionDTO option, SkillImportBudget budget) {
        String root = sourceRoot(option.source());
        byte[] data = bytes(root + "/git/trees/" + segment(option.treeSha()) + "?recursive=1",
                SkillGithubLimitConstant.MAX_TREE_BYTES, budget);
        JsonNode tree = parse(data);
        if (tree.path("truncated").asBoolean(true) || !tree.path("tree").isArray()
                || tree.path("tree").size() > SkillGithubLimitConstant.MAX_ENTRIES
                || !option.treeSha().equals(tree.path("sha").asText())) {
            throw rejected("SOURCE_LIMIT_EXCEEDED", "仓库目录过大或不完整，请上传所选Skill目录ZIP");
        }
        return data;
    }

    @Override
    public void download(SkillGithubSourceOptionDTO option, Path target, SkillImportBudget budget) {
        String location = execute(sourceRoot(option.source()) + "/zipball/" + option.source().commitSha(), budget,
                response -> {
                    if (response.getCode() != 302 || response.getFirstHeader("Location") == null) {
                        status(response);
                        throw rejected("SOURCE_REDIRECT_REJECTED", "仓库归档地址发生变化，请更新来源");
                    }
                    return response.getFirstHeader("Location").getValue();
                });
        URI uri;
        try {
            uri = URI.create(location);
        } catch (IllegalArgumentException exception) {
            throw rejected("SOURCE_REDIRECT_REJECTED", "仓库归档跳转不合法");
        }
        URI source = URI.create(option.source().repositoryUrl());
        String expected = source.getPath() + "/legacy.zip/" + option.source().commitSha();
        if (!"https".equals(uri.getScheme()) || !"codeload.github.com".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !expected.equals(uri.getRawPath())) {
            throw rejected("SOURCE_REDIRECT_REJECTED", "仓库归档目标与固定提交不符");
        }
        execute(uri.toString(), budget, response -> {
            status(response);
            try (OutputStream output = Files.newOutputStream(target)) {
                transfer(response, output, SkillGithubLimitConstant.MAX_ARCHIVE_BYTES, budget);
            }
            return null;
        });
    }

    private boolean directoryExists(String root, String treeSha, String directory, SkillImportBudget budget) {
        JsonNode tree = json(root + "/git/trees/" + treeSha + "?recursive=1",
                SkillGithubLimitConstant.MAX_TREE_BYTES, budget, false);
        if (tree.path("truncated").asBoolean(true) || !tree.path("tree").isArray()
                || tree.path("tree").size() > SkillGithubLimitConstant.MAX_ENTRIES) {
            throw rejected("SOURCE_LIMIT_EXCEEDED", "仓库目录过大，请上传所选Skill目录ZIP");
        }
        for (JsonNode entry : tree.path("tree")) {
            if (directory.equals(entry.path("path").asText()) && "tree".equals(entry.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode json(String url, int limit, SkillImportBudget budget, boolean allowMissing) {
        return execute(url, budget, response -> {
            if (allowMissing && (response.getCode() == 404 || response.getCode() == 422)) {
                return null;
            }
            status(response);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transfer(response, output, limit, budget);
            return parse(output.toByteArray());
        });
    }

    private byte[] bytes(String url, int limit, SkillImportBudget budget) {
        return execute(url, budget, response -> {
            status(response);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transfer(response, output, limit, budget);
            return output.toByteArray();
        });
    }

    private <T> T execute(String url, SkillImportBudget budget, ResponseReader<T> reader) {
        budget.check();
        HttpGet request = new HttpGet(url);
        request.setHeader("Accept", "application/vnd.github+json");
        request.setHeader("X-GitHub-Api-Version", "2026-03-10");
        request.setHeader("User-Agent", "Verse-Skill-Import");
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(3))
                .setResponseTimeout(Timeout.ofMilliseconds(Math.min(10000, Math.max(1, budget.remaining().toMillis()))))
                .build());
        ScheduledFuture<?> cancellation = scheduler.scheduleAtFixedRate(() -> {
            try {
                budget.check();
            } catch (SkillGithubSourceException exception) {
                request.cancel();
            }
        }, Duration.ofSeconds(1));
        try {
            return client.execute(request, response -> reader.read(response));
        } catch (IOException exception) {
            budget.check();
            throw new SkillGithubSourceException("SOURCE_UNAVAILABLE", "GitHub连接暂时不可用，正在有界重试", true, null);
        } finally {
            if (cancellation != null) {
                cancellation.cancel(false);
            }
            request.cancel();
        }
    }

    private void transfer(ClassicHttpResponse response, OutputStream output, long maximum, SkillImportBudget budget)
            throws IOException {
        if (response.getEntity() == null || response.getEntity().getContentLength() > maximum) {
            throw rejected("SOURCE_LIMIT_EXCEEDED", "仓库响应超过资源限制");
        }
        long total = 0;
        try (InputStream input = response.getEntity().getContent()) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) != -1) {
                budget.check();
                total += count;
                if (total > maximum) {
                    throw rejected("SOURCE_LIMIT_EXCEEDED", "仓库响应超过资源限制，请上传所选目录ZIP");
                }
                output.write(buffer, 0, count);
            }
        }
        budget.check();
        if (total == 0) {
            throw rejected("SOURCE_INTEGRITY_FAILED", "GitHub返回空归档或目录");
        }
    }

    private void status(ClassicHttpResponse response) {
        int code = response.getCode();
        if (code >= 200 && code < 300) {
            return;
        }
        if (code == 429 || (code == 403 && (response.getFirstHeader("Retry-After") != null
                || "0".equals(header(response, "x-ratelimit-remaining"))))) {
            throw new SkillGithubSourceException("GITHUB_RATE_LIMITED", "GitHub共享出口请求限流，请稍后重试", true,
                    retryAt(response));
        }
        if (code >= 500) {
            throw new SkillGithubSourceException("SOURCE_UNAVAILABLE", "GitHub暂时不可用，正在有界重试", true, null);
        }
        if (code >= 300 && code < 400) {
            throw rejected("SOURCE_REDIRECT_REJECTED", "仓库地址已变化，请使用规范的仓库新地址");
        }
        throw unavailable();
    }

    private Instant retryAt(ClassicHttpResponse response) {
        String retry = header(response, "Retry-After");
        String reset = header(response, "x-ratelimit-reset");
        Instant earliest = Instant.now().plusSeconds(60);
        try {
            if (retry != null) {
                earliest = retry.matches("[0-9]{1,10}") ? Instant.now().plusSeconds(Long.parseLong(retry))
                        : ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            }
            if (reset != null && reset.matches("[0-9]{1,12}")) {
                Instant resetAt = Instant.ofEpochSecond(Long.parseLong(reset));
                if (resetAt.isAfter(earliest)) {
                    earliest = resetAt;
                }
            }
        } catch (DateTimeParseException | ArithmeticException exception) {
            return Instant.now().plusSeconds(60);
        }
        return earliest;
    }

    private String header(ClassicHttpResponse response, String name) {
        Header header = response.getFirstHeader(name);
        return header == null ? null : header.getValue();
    }

    private JsonNode parse(byte[] bytes) {
        try {
            JsonNode value = mapper.readTree(bytes);
            if (value == null || !value.isObject()) {
                throw rejected("SOURCE_INTEGRITY_FAILED", "GitHub返回的目录或提交信息无法识别");
            }
            return value;
        } catch (IOException exception) {
            throw rejected("SOURCE_INTEGRITY_FAILED", "GitHub返回的目录或提交信息无法识别");
        }
    }

    private String sourceRoot(SkillSourceDTO source) {
        URI uri = URI.create(source.repositoryUrl());
        return "https://api.github.com/repos" + uri.getRawPath();
    }

    private String root(String owner, String repository) {
        return "https://api.github.com/repos/" + segment(owner) + "/" + segment(repository);
    }

    private String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private SkillGithubSourceException unavailable() {
        return rejected("REPOSITORY_UNAVAILABLE", "仓库不存在、非公开或暂不可访问");
    }

    private SkillGithubSourceException rejected(String code, String message) {
        return new SkillGithubSourceException(code, message, false, null);
    }

    @PreDestroy
    public void close() throws IOException {
        client.close();
    }

    @FunctionalInterface
    private interface ResponseReader<T> {
        T read(ClassicHttpResponse response) throws IOException;
    }
}
