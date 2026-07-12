package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 @CurrentUser 注解标记的参数，自动从 UserContextHolder 注入用户信息
 *
 * @author Yonagi
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && (parameter.getParameterType().equals(Long.class)
                    || parameter.getParameterType().equals(UserContext.class));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            throw new ClientException(BaseErrorCode.TOKEN_MISSING);
        }

        if (parameter.getParameterType().equals(Long.class)) {
            return ctx.getUserId();
        }
        return ctx;
    }
}
