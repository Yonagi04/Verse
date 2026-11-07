package com.yonagi.verse.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 外部认证回调跳转时，避免将含授权码的地址传给后续页面。 */
@Component
public class ExternalAuthHeadersFilter extends OncePerRequestFilter {
    private static final RequestMatcher CALLBACK =
            new AntPathRequestMatcher("/api/v1/auth/external/callback/*", "GET");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (CALLBACK.matches(request)) {
            response.setHeader("Referrer-Policy", "no-referrer");
        }
        chain.doFilter(request, response);
    }
}
