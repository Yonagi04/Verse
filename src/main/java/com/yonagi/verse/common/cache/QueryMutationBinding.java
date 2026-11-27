package com.yonagi.verse.common.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 静态登记写解析器的表和完整 statementId，启动发现不创建业务 Bean。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface QueryMutationBinding {
    /** 允许解析的完整目标表集合。 */
    String[] tables();

    /** 不可与其他解析器重复绑定的完整写语句标识。 */
    String[] statementIds();
}
