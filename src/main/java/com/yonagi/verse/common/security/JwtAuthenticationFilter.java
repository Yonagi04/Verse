package com.yonagi.verse.common.security;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.PermissionEnum;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.dao.projection.CurrentTenantState;
import com.yonagi.verse.service.CurrentTenantStateService;
import com.yonagi.verse.service.LoginSessionValidationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * JWT 认证过滤器 — 从请求头提取 Token，验证、查询权限并设置认证上下文
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final LoginSessionValidationService sessions;
    private final CurrentTenantStateService currentTenantStateService;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // SSE 完成时会异步重新派发；该派发仍需重建 JWT 认证上下文。
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // /ws 走 WebSocket 握手认证；/api/v1/openai/** 走 API Key 认证（ApiKeyAuthenticationFilter）
        return path.startsWith("/ws") || path.startsWith("/api/v1/openai/")
                || "/api/v1/rerank".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token == null) {
            // 无 Token → 放行，由 SecurityConfig 处理认证要求
            filterChain.doFilter(request, response);
            return;
        }

        LoginSessionValidationService.Identity identity;
        try {
            identity = sessions.authenticate(token);
        } catch (ClientException e) {
            writeErrorResponse(response, BaseErrorCode.TOKEN_EXPIRED.code().equals(e.getErrorCode())
                    ? BaseErrorCode.TOKEN_EXPIRED : BaseErrorCode.TOKEN_INVALID);
            return;
        }

        try {
            Long userId = identity.userId();
            // 每次请求均以数据库状态服务为权威来源，切换租户后无需重新签发 JWT。
            boolean accountScoped = request.getServletPath().equals("/api/v1/skills")
                    || request.getServletPath().startsWith("/api/v1/skills/");
            CurrentTenantState tenantState = accountScoped ? new CurrentTenantState()
                    : currentTenantStateService.resolveCurrentTenant(userId);
            Long activeTenantId = tenantState.getTenantId();
            RoleEnum role = parseRole(tenantState.getRole(), userId, activeTenantId);
            Set<PermissionEnum> permissions = role != null
                    ? role.getPermissions()
                    : Collections.emptySet();

            // 构建 UserContext
            UserContext ctx = new UserContext()
                    .setUserId(userId)
                    .setUsername(identity.username())
                    .setTokenHash(identity.tokenHash())
                    .setCurrentTenantId(activeTenantId)
                    .setRole(role != null ? role.name() : null)
                    .setAuthorities(permissions.stream().map(PermissionEnum::getCode).toList());
            UserContextHolder.set(ctx);

            // 构建 Spring Security Authentication（让 @PreAuthorize 生效）
            List<GrantedAuthority> authorities = new ArrayList<>();
            permissions.forEach(p ->
                    authorities.add(new SimpleGrantedAuthority(p.getCode())));
            if (role != null) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(ctx, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private RoleEnum parseRole(String role, Long userId, Long tenantId) {
        if (role == null) {
            return null;
        }
        try {
            return RoleEnum.valueOf(role);
        } catch (IllegalArgumentException e) {
            log.warn("用户 {} 在租户 {} 中的角色未知: {}", userId, tenantId, role);
            return null;
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void writeErrorResponse(HttpServletResponse response, BaseErrorCode errorCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = Results.failure(errorCode.code(), errorCode.message());
        response.getWriter().write(JSON.toJSONString(result));
    }
}
