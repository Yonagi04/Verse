package com.yonagi.verse.common.cache;

import java.lang.annotation.*;

/** 手工 read 入口在所属 Bean 上声明依赖，与注解查询共用失效目录。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface QueryCacheDependencies {
    String[] value();
}
