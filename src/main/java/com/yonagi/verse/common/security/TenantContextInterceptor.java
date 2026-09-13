package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 在控制器执行前统一强制当前租户边界。
 */
@Component
public class TenantContextInterceptor implements HandlerInterceptor {

    private final boolean isolationEnabled;

    public TenantContextInterceptor(
            @Value("${verse.tenant.context-isolation-enabled:true}") boolean isolationEnabled) {
        this.isolationEnabled = isolationEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isolationEnabled || !(handler instanceof HandlerMethod handlerMethod)
                || isExempt(handlerMethod)) {
            return true;
        }

        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables) || !variables.containsKey("tenantId")) {
            return true;
        }

        // 未认证请求继续交给 Spring Security；已认证但无租户与租户不一致使用同一错误。
        UserContext context = UserContextHolder.get();
        if (context == null) {
            return true;
        }
        Long requestTenantId = parseTenantId(variables.get("tenantId"));
        if (requestTenantId == null || context.getCurrentTenantId() == null
                || !requestTenantId.equals(context.getCurrentTenantId())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_CONTEXT_MISMATCH);
        }
        return true;
    }

    private boolean isExempt(HandlerMethod handlerMethod) {
        TenantContextExempt methodAnnotation = handlerMethod.getMethodAnnotation(TenantContextExempt.class);
        TenantContextExempt typeAnnotation = handlerMethod.getBeanType().getAnnotation(TenantContextExempt.class);
        return hasReason(methodAnnotation) || hasReason(typeAnnotation);
    }

    private boolean hasReason(TenantContextExempt annotation) {
        return annotation != null && annotation.reason() != null && !annotation.reason().isBlank();
    }

    private Long parseTenantId(Object value) {
        try {
            return value != null ? Long.valueOf(value.toString()) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
