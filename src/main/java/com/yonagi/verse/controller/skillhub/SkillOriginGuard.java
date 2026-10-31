package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;

/** 本域匿名写入拒绝跨站简单请求，不继承旧API通配CORS的信任边界。 */
@Component
public class SkillOriginGuard {
    private final URI frontendOrigin;

    /**
     * @param frontendBaseUrl 既有前端地址，复用基础设施设置而非新增Skill配置
     */
    public SkillOriginGuard(@Value("${verse.frontend-baseurl}") String frontendBaseUrl) {
        frontendOrigin = URI.create(frontendBaseUrl);
    }

    /**
     * @param request 必须由可信前端或实际同源发起的JSON领取请求
     */
    public void requireTrusted(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equals(fetchSite)) {
            throw denied();
        }
        if (origin == null) {
            if (!"same-origin".equals(fetchSite)) {
                throw denied();
            }
            return;
        }
        URI candidate;
        try {
            candidate = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw denied();
        }
        URI actual = URI.create(request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort());
        if (!sameOrigin(candidate, frontendOrigin) && !sameOrigin(candidate, actual)) {
            throw denied();
        }
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getHost() != null && left.getUserInfo() == null && left.getQuery() == null
                && left.getFragment() == null && (left.getPath() == null || left.getPath().isEmpty())
                && left.getScheme() != null && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost()) && port(left) == port(right);
    }

    private int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private ClientException denied() {
        return new ClientException("请从Verse站内发起下载", SkillErrorCodeEnum.INVALID_INPUT);
    }
}
