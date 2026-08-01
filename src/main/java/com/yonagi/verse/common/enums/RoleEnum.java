package com.yonagi.verse.common.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * 租户角色枚举 — 定义角色及其对应的权限集合
 *
 * <p>角色与权限映射在 {@link #getPermissions()} 中集中管理。
 * 新增权限时，只需在 {@link PermissionEnum} 中添加，然后在此处对应角色下添加即可。
 *
 * @author Yonagi
 */
public enum RoleEnum {

    SUPER_ADMIN,
    ADMIN,
    MEMBER;

    public boolean isAdmin() {
        return this == ADMIN || this == SUPER_ADMIN;
    }

    /**
     * 返回该角色拥有的全部权限
     */
    public Set<PermissionEnum> getPermissions() {
        return switch (this) {
            case SUPER_ADMIN -> EnumSet.allOf(PermissionEnum.class);

            case ADMIN -> EnumSet.of(
                // 用户自身
                PermissionEnum.USER_PROFILE_READ,
                PermissionEnum.USER_PROFILE_WRITE,
                PermissionEnum.USER_PASSWORD_WRITE,
                // 租户（不含 DELETE）
                PermissionEnum.TENANT_CREATE,
                PermissionEnum.TENANT_SETTINGS_WRITE,
                PermissionEnum.TENANT_READ,
                // 成员管理（不含 ROLE_WRITE）
                PermissionEnum.TENANT_MEMBER_INVITE,
                PermissionEnum.TENANT_MEMBER_REMOVE,
                PermissionEnum.TENANT_MEMBER_READ,
                // LLM 服务管理
                PermissionEnum.TENANT_LLM_REGISTER,
                PermissionEnum.TENANT_LLM_UPDATE,
                PermissionEnum.TENANT_LLM_DELETE,
                PermissionEnum.TENANT_LLM_READ,
                PermissionEnum.TENANT_LLM_CREDENTIAL_READ,
                // Token + 日志 + 限流
                PermissionEnum.TENANT_TOKENS_READ_OWN,
                PermissionEnum.TENANT_TOKENS_READ_ALL,
                PermissionEnum.TENANT_LOGS_READ_OWN,
                PermissionEnum.TENANT_LOGS_READ_ALL,
                PermissionEnum.TENANT_RATELIMIT_WRITE,
                // API Key
                PermissionEnum.API_KEY_CREATE,
                PermissionEnum.API_KEY_DELETE,
                PermissionEnum.API_KEY_READ
            );

            case MEMBER -> EnumSet.of(
                // 用户自身
                PermissionEnum.USER_PROFILE_READ,
                PermissionEnum.USER_PROFILE_WRITE,
                PermissionEnum.USER_PASSWORD_WRITE,
                // 租户只读
                PermissionEnum.TENANT_READ,
                // 成员只读
                PermissionEnum.TENANT_MEMBER_READ,
                // LLM 服务只读
                PermissionEnum.TENANT_LLM_READ,
                // 自己的 Token + 日志
                PermissionEnum.TENANT_TOKENS_READ_OWN,
                PermissionEnum.TENANT_LOGS_READ_OWN,
                // API Key
                PermissionEnum.API_KEY_CREATE,
                PermissionEnum.API_KEY_DELETE,
                PermissionEnum.API_KEY_READ
            );
        };
    }

    /**
     * 权限比较, 超级管理员>管理员>成员
     * @param other 目标角色
     * @return true 当前角色权限高于目标角色, false 否则
     */
    public boolean isNotLowerThan(RoleEnum other) {
        if (this == SUPER_ADMIN) {
            return true;
        } else if (this == ADMIN) {
            return other != SUPER_ADMIN;
        } else {
            return false;
        }
    }

    /**
     * 判断角色是否存在
     */
    public static boolean isValidRole(String role) {
        for (RoleEnum r : RoleEnum.values()) {
            if (r.name().equals(role)) {
                return true;
            }
        }
        return false;
    }
}
