package com.yonagi.verse.common.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 外部认证不缓存凭证或回调，也不向后续页面传递Referer。 */
@Component
public class ExternalAuthHeadersFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if (request.getRequestURI().startsWith("/api/v1/auth/external") || request.getRequestURI().startsWith("/api/v1/users/me/external-accounts")) {
            response.setHeader("Cache-Control","no-store"); response.setHeader("Referrer-Policy","no-referrer");
        }
        chain.doFilter(request,response);
    }
}
