package com.yonagi.verse.common.security;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

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
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String OPENAI_PATH_PREFIX = "/api/v1/openai";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 防缓存穿透的空值标记
     */
    private static final String NULL_MARKER = "__NULL__";

    private static final long CACHE_TTL_MINUTES = 10;
    private static final long NULL_CACHE_TTL_MINUTES = 1;

    private final StringRedisTemplate stringRedisTemplate;
    private final ApiKeyMapper apiKeyMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(OPENAI_PATH_PREFIX);
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

        ApiKeyDO apiKey = loadApiKey(token);
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

        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }

    private ApiKeyDO loadApiKey(String token) {
        String hash = DigestUtil.sha256Hex(token);
        String cacheKey = RedisKeyConstant.API_KEY_AUTH_KEY + hash;

        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (NULL_MARKER.equals(cached)) {
                return null;
            }
            return JSON.parseObject(cached, ApiKeyDO.class);
        }

        ApiKeyDO apiKey = apiKeyMapper.selectOne(Wrappers.lambdaQuery(ApiKeyDO.class)
                .eq(ApiKeyDO::getApiKey, hash));
        if (apiKey == null) {
            // 防穿透：短暂缓存空标记
            stringRedisTemplate.opsForValue().set(cacheKey, NULL_MARKER, NULL_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(apiKey), CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        return apiKey;
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
