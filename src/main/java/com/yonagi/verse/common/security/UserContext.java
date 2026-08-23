package com.yonagi.verse.common.security;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 用户上下文 — 存储当前请求的用户信息及权限
 *
 * @author Yonagi
 */
@Data
@Accessors(chain = true)
public class UserContext {

    /**
     * 用户业务 ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 当前活跃租户 ID
     */
    private Long currentTenantId;

    /**
     * 用户在当前活跃租户下的角色（SUPER_ADMIN / ADMIN / MEMBER）
     */
    private String role;

    /**
     * 用户在当前活跃租户下的权限 code 列表，对应 {@link com.yonagi.verse.common.enums.PermissionEnum#getCode()}
     */
    private List<String> authorities;

    /**
     * API Key 业务 ID（API Key 认证场景写入，JWT 场景为 null）
     */
    private Long apiKeyId;
}
