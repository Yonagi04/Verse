package com.yonagi.verse.controller;

import com.yonagi.verse.common.config.ExternalAuthProperties;
import com.yonagi.verse.service.external.ExternalAuthCookieOutput;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** 外部认证的 Cookie 和跳转响应适配，避免协议对象进入用例。 */
@Component
@RequiredArgsConstructor
class ExternalAuthResponse {
    private static final String COOKIE_PATH = "/api/v1";

    private final ExternalAuthProperties properties;

    ExternalAuthCookieOutput cookies(HttpServletResponse response) {
        return (name, value, ttl) -> {
            ResponseCookie cookie = ResponseCookie.from(name, value)
                    .httpOnly(true)
                    .secure(properties.getFrontendOrigin().startsWith("https://"))
                    .sameSite("Lax")
                    .path(COOKIE_PATH)
                    .maxAge(ttl)
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        };
    }

    ResponseEntity<Void> redirect(HttpServletResponse response, Supplier<String> destination) {
        // 回调清理失败也沿用已声明的 303，异常映射负责响应体。
        response.setStatus(HttpStatus.SEE_OTHER.value());
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, destination.get()).build();
    }
}
