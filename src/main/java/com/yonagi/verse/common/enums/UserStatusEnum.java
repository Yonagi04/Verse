package com.yonagi.verse.common.enums;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/25 21:20
 */
public enum UserStatusEnum {

    USER_STATUS_DISABLED(0),
    USER_STATUS_ACTIVE(1),
    USER_STATUS_CLOSED(2);

    private final Integer status;

    UserStatusEnum(Integer status) {
        this.status = status;
    }

    public Integer getStatusCode() {
        return status;
    }
}
