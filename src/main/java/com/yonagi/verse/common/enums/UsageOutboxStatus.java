package com.yonagi.verse.common.enums;

/** 计费用量事件 Outbox 状态。 */
public enum UsageOutboxStatus {
    PENDING,
    CLAIMED,
    RETRY,
    FAILED,
    PUBLISHED
}
