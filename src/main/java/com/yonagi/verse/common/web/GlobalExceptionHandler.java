package com.yonagi.verse.common.web;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;
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

    private static final int MAX_LOG_STACK_FRAMES = 12;

    /** 精确金额解码失败仍返回管理接口业务错误，不能落入未知服务异常。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public Result<?> unreadable(org.springframework.http.converter.HttpMessageNotReadableException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof AbstractException business) return Results.failure(business);
            cause = cause.getCause();
        }
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "请求参数格式不正确");
    }
    /** 保留来源拒绝及限频的真实HTTP状态，供外部认证界面就地处理。 */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> responseStatus(org.springframework.web.server.ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(
                Results.failure(BaseErrorCode.CLIENT_ERROR.code(), ex.getReason()));
    }

    /** 返回首个字段校验提示，日志只记录校验错误数量。 */
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result<Void> validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        String exceptionMessage = Optional.ofNullable(bindingResult.getFieldError())
                .map(FieldError::getDefaultMessage)
                .orElse("");
        log.error("[{}] {} 参数校验失败: errorCount={}", request.getMethod(), routePattern(request),
                bindingResult.getErrorCount());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionMessage);
    }

    /** 保留领域错误响应，日志以错误码定位业务失败。 */
    @ExceptionHandler(value = {AbstractException.class})
    public Result<Void> abstractException(HttpServletRequest request, AbstractException ex) {
        if (ex.getCause() != null) {
            logFailure(request, ex.getErrorCode(), ex);
        } else {
            log.error("[{}] {} 业务请求失败: code={}, type={}", request.getMethod(), routePattern(request),
                    ex.getErrorCode(), ex.getClass().getSimpleName());
        }
        return Results.failure(ex);
    }

    /**
     * 拦截认证异常
     */
    @ExceptionHandler(value = {AuthenticationException.class})
    public Result<Void> handleAuthenticationException(HttpServletRequest request, AuthenticationException ex) {
        log.warn("[{}] {} 认证失败: type={}", request.getMethod(), routePattern(request),
                ex.getClass().getSimpleName());
        return Results.failure(BaseErrorCode.TOKEN_INVALID.code(), "认证失败");
    }

    /**
     * 拦截权限不足异常
     */
    @ExceptionHandler(value = {AccessDeniedException.class})
    public Result<Void> handleAccessDeniedException(HttpServletRequest request, AccessDeniedException ex) {
        log.warn("[{}] {} 权限不足", request.getMethod(), routePattern(request));
        return Results.failure(BaseErrorCode.TOKEN_INVALID.code(), "权限不足");
    }

    /**
     * 拦截查询参数类型不匹配，例如 limit 不是有效整数。
     */
    @ExceptionHandler(value = MethodArgumentTypeMismatchException.class)
    public Result<Void> typeMismatchExceptionHandler(HttpServletRequest request,
                                                      MethodArgumentTypeMismatchException ex) {
        String message = "请求参数格式不正确";
        log.warn("[{}] {} [parameter] {}", request.getMethod(), routePattern(request), ex.getName());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message);
    }

    /**
     * 拦截 multipart 文件大小超限异常。该异常发生在进入控制器之前，需要在此转换为统一响应。
     */
    @ExceptionHandler(value = {MaxUploadSizeExceededException.class})
    public Result<Void> handleMaxUploadSizeExceededException(HttpServletRequest request,
                                                              MaxUploadSizeExceededException ex) {
        log.warn("[{}] {} 图片大小超过上传上限: maxUploadSize={}",
                request.getMethod(), routePattern(request), ex.getMaxUploadSize());
        return Results.failure(BaseErrorCode.UPLOAD_FILE_SIZE_EXCEED.code(),
                BaseErrorCode.UPLOAD_FILE_SIZE_EXCEED.message());
    }

    /** 将可处理的未知异常转换为通用失败，致命错误继续传播。 */
    @ExceptionHandler(value = {Throwable.class})
    public Result<Void> throwableException(HttpServletRequest request, Throwable throwable) {
        FatalErrors.rethrowIfFatal(throwable);
        logFailure(request, BaseErrorCode.SERVICE_ERROR.code(), throwable);
        return Results.failure();
    }

    private void logFailure(HttpServletRequest request, String errorCode, Throwable failure) {
        Throwable cause = failure.getCause();
        Throwable diagnostic = cause == null ? failure : cause;
        // 异常消息可能回显请求值或依赖响应，只保留异常类型和有限的调用位置。
        log.error("[{}] {} 请求失败: code={}, type={}, diagnosticType={}, stack={}",
                request.getMethod(), routePattern(request), errorCode, failure.getClass().getSimpleName(),
                diagnostic.getClass().getSimpleName(),
                Arrays.stream(diagnostic.getStackTrace()).limit(MAX_LOG_STACK_FRAMES).toList());
    }

    private String routePattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route ? route : "<unmatched>";
    }
}
