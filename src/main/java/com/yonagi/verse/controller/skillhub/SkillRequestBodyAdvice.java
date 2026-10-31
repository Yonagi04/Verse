package com.yonagi.verse.controller.skillhub;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/** 限制发布表单体积并拒绝伪造归属等未知字段，不修改其他业务的JSON兼容策略。 */
@ControllerAdvice(basePackages = "com.yonagi.verse.controller.skillhub")
@RequiredArgsConstructor
public class SkillRequestBodyAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper objectMapper;

    /** 仅作用于本域JSON表单，不截获原始ZIP流。 */
    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.getParameterType().getPackageName().equals("com.yonagi.verse.dto.req");
    }

    /** 在绑定前执行有界读取，所有权始终来自认证上下文。 */
    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter,
                                           Type targetType, Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] body = input.getBody().readNBytes(SkillPackageLimitConstant.MAX_FORM_BYTES + 1);
        if (body.length > SkillPackageLimitConstant.MAX_FORM_BYTES) {
            throw new ClientException(SkillErrorCodeEnum.LIMIT_EXCEEDED);
        }
        try {
            objectMapper.readerFor(parameter.getParameterType())
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (IOException exception) {
            throw new HttpMessageNotReadableException("Skill表单字段或格式不合法", exception, input);
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }
}
