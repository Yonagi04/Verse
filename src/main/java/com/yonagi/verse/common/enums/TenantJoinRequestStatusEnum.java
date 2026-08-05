package com.yonagi.verse.common.enums;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/05 19:33
 */
public enum TenantJoinRequestStatusEnum {

    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    PENDING("PENDING");

    private final String status;

    TenantJoinRequestStatusEnum(String status) {
        this.status = status;
    }

    public String getStatus() {
        return status;
    }
}
