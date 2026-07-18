package com.yonagi.verse.common.config;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置 — JWT 认证 + 方法级鉴权
 *
 * @author Yonagi
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * JWT 认证白名单路径，从 yml 配置读取（如 LLM 调用接口使用 API Key 独立认证）
     */
    @Value("${verse.security.ignore-paths}")
    private String ignorePathsStr;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        List<String> ignorePaths = Arrays.asList(ignorePathsStr.split(","));
        RequestMatcher[] ignoreMatchers = ignorePaths.stream()
                .map(AntPathRequestMatcher::new)
                .toArray(RequestMatcher[]::new);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ignoreMatchers).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            Result<Void> result = Results.failure(
                                    BaseErrorCode.TOKEN_MISSING.code(),
                                    BaseErrorCode.TOKEN_MISSING.message());
                            response.getWriter().write(JSON.toJSONString(result));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            Result<Void> result = Results.failure(
                                    BaseErrorCode.TOKEN_INVALID.code(), "权限不足");
                            response.getWriter().write(JSON.toJSONString(result));
                        })
                );

        return http.build();
    }
}
