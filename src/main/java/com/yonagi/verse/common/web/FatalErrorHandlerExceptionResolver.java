package com.yonagi.verse.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** 先于 MVC 普通 resolver 执行；在 @ExceptionHandler 内重抛仍可能被框架接管并映射为 400。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FatalErrorHandlerExceptionResolver implements HandlerExceptionResolver {
    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception exception) {
        FatalErrors.rethrowIfFatal(exception);
        return null;
    }
}
