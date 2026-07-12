package com.yonagi.verse.common.security;

import com.yonagi.verse.common.enums.RoleEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色权限注解 — 标记在 Controller 方法上，限定可访问的角色
 *
 * <p>使用示例：
 * <pre>{@code
 * // 仅管理员可访问（方法参数中必须有 @PathVariable("tenantId")）
 * @RequireRole({RoleEnum.ADMIN, RoleEnum.SUPER_ADMIN})
 * @PutMapping("/{tenantId}/users/{userId}/role")
 * public Result<Void> updateUserRole(...) { }
 * }</pre>
 *
 * @author Yonagi
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /**
     * 允许访问的角色列表
     */
    RoleEnum[] value();
}
