package com.yonagi.verse.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式放行路径租户上下文校验；使用方必须记录业务原因。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantContextExempt {

    /** 放行原因，禁止使用空字符串。 */
    String reason();
}
