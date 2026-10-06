package com.yonagi.verse.common.security;

import com.yonagi.verse.common.cache.QueryCacheDependencies;

import com.yonagi.verse.common.cache.QueryCacheTtl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.cache.QueryCache;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.service.impl.ApiKeyUsageRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;
import java.util.List;

/**
 * API Key 认证过滤器 — 仅处理 /api/v1/openai/**，解析 Bearer sk_xxx，
 * SHA-256 后查缓存/DB，校验状态与有效期，写入 UserContext。
 *
 * <p>失败时返回 OpenAI 兼容错误格式（非 Result 包装）。</p>
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
@QueryCacheDependencies({"t_api_key"})
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String OPENAI_PATH_PREFIX = "/api/v1/openai";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final QueryCache queryCache;
    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyUsageRecorder apiKeyUsageRecorder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !(path.startsWith(OPENAI_PATH_PREFIX + "/") || "/api/v1/rerank".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            writeOpenAiError(response, HttpServletResponse.SC_UNAUTHORIZED, LlmForwardErrorCodeEnum.API_KEY_INVALID);
            return;
        }

        ApiKeyDO apiKey;
        try { apiKey = loadApiKey(token); }
        catch (ServerException | org.springframework.dao.DataAccessException error) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":{\"message\":\"Authentication temporarily unavailable\","
                    + "\"type\":\"server_error\",\"code\":\"authentication_unavailable\"}}");
            return;
        }
        if (apiKey == null || !isKeyValid(apiKey)) {
            writeOpenAiError(response, HttpServletResponse.SC_UNAUTHORIZED, LlmForwardErrorCodeEnum.API_KEY_INVALID);
            return;
        }

        UserContext ctx = new UserContext()
                .setUserId(apiKey.getUserId())
                .setCurrentTenantId(apiKey.getTenantId())
                .setApiKeyId(apiKey.getApiKeyId())
                .setApiKeyRateLimitRpm(apiKey.getRateLimitRpm())
                .setApiKeyRateLimitTpm(apiKey.getRateLimitTpm());
        UserContextHolder.set(ctx);
        // API Key 路由也建立 Spring Security 认证，供精确路径和后续安全规则统一识别。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ctx, null, List.of()));
        // 合法 Key 每次通过鉴权后异步更新最近使用时间，不等待模型响应或计费事件。
        apiKeyUsageRecorder.record(apiKey.getApiKeyId(), new Date());

        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private ApiKeyDO loadApiKey(String token) {
        String hash = DigestUtil.sha256Hex(token);
        Long id = queryCache.read("api-key-id", RedisKeyConstant.API_KEY_AUTH_ID_KEY, hash, Long.class, List.of("t_api_key"), java.util.concurrent.TimeUnit.SECONDS.toMillis(QueryCacheTtl.HOURS_4), () -> {
            ApiKeyDO key = apiKeyMapper.selectOne(Wrappers.lambdaQuery(ApiKeyDO.class)
                    .select(ApiKeyDO::getApiKeyId).eq(ApiKeyDO::getApiKey, hash));
            return key == null ? null : key.getApiKeyId();
        });
        if (id == null) return null;
        // 即使缓存失效链路异常，撤销、过期、限流与租户归属也以这次实时查询为准。
        return queryCache.check(() -> apiKeyMapper.selectAuthState(id, hash));
    }

    private boolean isKeyValid(ApiKeyDO apiKey) {
        if (!Integer.valueOf(1).equals(apiKey.getStatus())) {
            return false;
        }
        Date expiresAt = apiKey.getExpiresAt();
        return expiresAt == null || expiresAt.after(new Date());
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void writeOpenAiError(HttpServletResponse response, int status, LlmForwardErrorCodeEnum errorCode) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        JSONObject body = new JSONObject();
        JSONObject error = new JSONObject();
        error.put("message", errorCode.message());
        error.put("type", "invalid_request_error");
        error.put("code", "invalid_api_key");
        body.put("error", error);
        response.getWriter().write(JSON.toJSONString(body));
    }
}
