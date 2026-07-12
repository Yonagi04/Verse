package com.yonagi.verse.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Controller 方法参数，自动注入当前登录用户的 ID 或 UserContext
 *
 * <pre>
 * // 直接获取 userId
 * &#64;GetMapping("/me")
 * public Result&lt;UserRespDTO&gt; getCurrentUser(&#64;CurrentUser Long userId) { ... }
 *
 * // 获取完整 UserContext
 * &#64;GetMapping("/me")
 * public Result&lt;UserRespDTO&gt; getCurrentUser(&#64;CurrentUser UserContext ctx) { ... }
 * </pre>
 *
 * @author Yonagi
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
