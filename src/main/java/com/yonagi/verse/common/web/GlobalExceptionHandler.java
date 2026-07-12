package com.yonagi.verse.common.web;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Optional;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/06/18 15:23
 */
@Component
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 拦截参数不合法异常
     * @param request
     * @param ex
     * @return
     */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        FieldError firstError = CollectionUtil.getFirst(bindingResult.getFieldErrors());
        String exceptionMessage = Optional.ofNullable(firstError)
                .map(FieldError::getDefaultMessage)
                .orElse(StrUtil.EMPTY);
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionMessage);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionMessage);
    }

    /**
     * 拦截业务异常
     * @param request
     * @param ex
     * @return
     */
    @ExceptionHandler(value = {AbstractException.class})
    public Result abstractException(HttpServletRequest request, AbstractException ex) {
        if (ex.getCause() != null) {
            log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex.toString(), ex.getCause());
            return Results.failure(ex);
        }

        log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex.toString());
        return Results.failure(ex);
    }

    /**
     * 拦截认证异常
     */
    @ExceptionHandler(value = {AuthenticationException.class})
    public Result handleAuthenticationException(HttpServletRequest request, AuthenticationException ex) {
        log.warn("[{}] {} 认证失败: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.TOKEN_INVALID.code(), "认证失败");
    }

    /**
     * 拦截权限不足异常
     */
    @ExceptionHandler(value = {AccessDeniedException.class})
    public Result handleAccessDeniedException(HttpServletRequest request, AccessDeniedException ex) {
        log.warn("[{}] {} 权限不足", request.getMethod(), getUrl(request));
        return Results.failure(BaseErrorCode.TOKEN_INVALID.code(), "权限不足");
    }

    /**
     * 拦截其他异常
     * @param request
     * @param throwable
     * @return
     */
    @ExceptionHandler(value = {Throwable.class})
    public Result throwableException(HttpServletRequest request, Throwable throwable) {
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        return Results.failure();
    }

    private String getUrl(HttpServletRequest request) {
        if (!StringUtils.hasLength(request.getQueryString())) {
            return request.getRequestURL().toString();
        }
        return request.getRequestURL().toString() + "?" + request.getQueryString();
    }
}
