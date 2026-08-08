package com.yonagi.verse.common.security;

import com.alibaba.fastjson2.JSON;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.PermissionEnum;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserMapper userMapper;
    private final UserTenantMapper userTenantMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/ws");
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

        try {
            Claims claims = jwtUtil.parseToken(token);

            // 校验 Token 是否已被登出（从 Redis 反向索引中查询）
            String tokenHash = DigestUtil.md5Hex(token);
            String userIdFromRedis = stringRedisTemplate.opsForValue()
                    .get(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash);
            if (userIdFromRedis == null) {
                writeErrorResponse(response, BaseErrorCode.TOKEN_INVALID);
                return;
            }

            Long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);

            // 查询用户在当前活跃租户下的角色和权限
            Long activeTenantId = getActiveTenantId(userId);
            RoleEnum role = getUserRole(userId, activeTenantId);
            Set<PermissionEnum> permissions = role != null
                    ? role.getPermissions()
                    : Collections.emptySet();

            // 构建 UserContext
            UserContext ctx = new UserContext()
                    .setUserId(userId)
                    .setUsername(username)
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
        } catch (ExpiredJwtException e) {
            log.debug("Token 已过期: {}", e.getMessage());
            writeErrorResponse(response, BaseErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token 无效: {}", e.getMessage());
            writeErrorResponse(response, BaseErrorCode.TOKEN_INVALID);
        } finally {
            UserContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 获取用户的当前活跃租户 ID
     */
    private Long getActiveTenantId(Long userId) {
        UserDO userDO = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUserId, userId)
                        .eq(UserDO::getDelFlag, 0));
        if (userDO != null && userDO.getLastActiveTenantId() != null) {
            return userDO.getLastActiveTenantId();
        }
        return null;
    }

    /**
     * 获取用户在指定租户下的角色
     */
    private RoleEnum getUserRole(Long userId, Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        UserTenantDO membership = userTenantMapper.selectOne(
                Wrappers.lambdaQuery(UserTenantDO.class)
                        .eq(UserTenantDO::getUserId, userId)
                        .eq(UserTenantDO::getTenantId, tenantId)
                        .isNull(UserTenantDO::getLeftAt));
        if (membership == null) {
            return null;
        }
        try {
            return RoleEnum.valueOf(membership.getRole());
        } catch (IllegalArgumentException e) {
            log.warn("用户 {} 在租户 {} 中的角色未知: {}", userId, tenantId, membership.getRole());
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
