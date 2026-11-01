package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dto.req.SkillGithubImportCreateReqDTO;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** URL只解析为已知GitHub资源，不将用户URL交给网络客户端。 */
@Component
public class SkillGithubSourcePolicy {
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern UNSAFE_REF = Pattern.compile("[\\x00-\\x20~^:?*\\[\\\\\\x7f]");
    private static final Set<String> REF_TYPES = Set.of("DEFAULT", "AUTO", "BRANCH", "TAG", "COMMIT");

    /** @return 严格规范输入；未知主机、编码穿越和不一致显式tree拆分拒绝 */
    public SkillGithubSourceRequest parse(SkillGithubImportCreateReqDTO input) {
        if (input == null || input.repositoryUrl() == null || input.repositoryUrl().length() > 2048) {
            throw invalid();
        }
        URI uri;
        try {
            uri = new URI(input.repositoryUrl());
        } catch (URISyntaxException exception) {
            throw invalid();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawPath() == null || input.repositoryUrl().indexOf('\\') >= 0) {
            throw invalid();
        }
        String path = uri.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String[] raw = path.split("/", -1);
        if (raw.length < 3 || !raw[0].isEmpty()) {
            throw invalid();
        }
        List<String> parts = new ArrayList<>();
        for (int index = 1; index < raw.length; index++) {
            String decoded;
            try {
                decoded = URLDecoder.decode(raw[index].replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw invalid();
            }
            checkSegment(decoded);
            parts.add(decoded);
        }
        String owner = parts.get(0);
        String repository = parts.get(1);
        if (repository.endsWith(".git")) {
            repository = repository.substring(0, repository.length() - 4);
        }
        if (!OWNER.matcher(owner).matches() || !REPOSITORY.matcher(repository).matches()
                || repository.equals(".") || repository.equals("..")) {
            throw invalid();
        }
        String ref = blank(input.ref());
        String refType = blank(input.refType());
        refType = refType == null ? (ref == null ? "DEFAULT" : "AUTO") : refType;
        if (!REF_TYPES.contains(refType) || ("DEFAULT".equals(refType) && ref != null)
                || (!"DEFAULT".equals(refType) && ref == null && parts.size() == 2)) {
            throw invalid();
        }
        if (ref != null) {
            validateRef(ref);
            if ("COMMIT".equals(refType) && !SHA.matcher(ref).matches()) {
                throw invalid();
            }
        }
        String directory = blank(input.directory());
        directory = directory == null ? "" : validateDirectory(directory);
        String tail = "";
        if (parts.size() > 2) {
            if (parts.size() < 4 || !"tree".equals(parts.get(2))) {
                throw invalid();
            }
            tail = String.join("/", parts.subList(3, parts.size()));
            if (ref != null && !tail.equals(ref + (directory.isEmpty() ? "" : "/" + directory))) {
                throw invalid();
            }
            if (ref == null && !directory.isEmpty()) {
                throw invalid();
            }
        }
        return new SkillGithubSourceRequest(owner, repository, refType, ref, directory, tail);
    }

    /** @return 有界ref/目录切分，未完整枚举时拒绝而不猜测 */
    public List<SkillGithubSourceRequest> splits(SkillGithubSourceRequest request) {
        if (request.treeTail().isEmpty() || request.ref() != null) {
            return List.of(request);
        }
        String[] segments = request.treeTail().split("/");
        if (segments.length > 10) {
            throw new ClientException("请改填仓库根地址和明确的引用、目录", SkillErrorCodeEnum.INVALID_INPUT);
        }
        List<SkillGithubSourceRequest> values = new ArrayList<>();
        for (int index = 1; index <= segments.length; index++) {
            String ref = String.join("/", List.of(segments).subList(0, index));
            validateRef(ref);
            String directory = String.join("/", List.of(segments).subList(index, segments.length));
            validateDirectory(directory);
            values.add(new SkillGithubSourceRequest(request.owner(), request.repository(),
                    SHA.matcher(ref).matches() ? "COMMIT" : "AUTO", ref, directory, request.treeTail()));
        }
        return List.copyOf(values);
    }

    /** @return 规范仓库相对目录，根目录使用空串 */
    public String validateDirectory(String value) {
        if (value.length() > 512 || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            throw invalid();
        }
        if (!value.isEmpty()) {
            String[] parts = value.split("/", -1);
            if (parts.length > 10) {
                throw invalid();
            }
            for (String part : parts) {
                checkSegment(part);
            }
        }
        return value;
    }

    /** @return 是否为本期支持的完整提交标识 */
    public boolean fullSha(String value) {
        return value != null && SHA.matcher(value).matches();
    }

    private void validateRef(String value) {
        if (value.length() > 255 || UNSAFE_REF.matcher(value).find() || value.contains("..")
                || value.contains("@{") || value.equals("@") || value.startsWith("/") || value.endsWith("/")
                || value.endsWith(".") || value.contains("//")) {
            throw invalid();
        }
        for (String part : value.split("/")) {
            if (part.startsWith(".") || part.toLowerCase(Locale.ROOT).endsWith(".lock")) {
                throw invalid();
            }
        }
    }

    private void checkSegment(String part) {
        if (part.isBlank() || part.equals(".") || part.equals("..") || part.contains("%")
                || part.contains("/") || part.contains("\\") || part.length() > 255) {
            throw invalid();
        }
        for (int index = 0; index < part.length(); index++) {
            char value = part.charAt(index);
            if (Character.isISOControl(value) || Character.getType(value) == Character.FORMAT) {
                throw invalid();
            }
        }
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ClientException invalid() {
        return new ClientException("请提供合法的公开GitHub仓库地址、引用与目录", SkillErrorCodeEnum.INVALID_INPUT);
    }
}
