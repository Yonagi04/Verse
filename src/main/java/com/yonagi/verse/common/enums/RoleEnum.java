package com.yonagi.verse.common.enums;

/**
 * 租户角色枚举
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
}
