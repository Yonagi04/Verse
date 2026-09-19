package com.yonagi.verse.common.enums;

/** 通用可靠事件 Outbox 状态。 */
public enum DomainEventOutboxStatus {
    PENDING, CLAIMED, RETRY, FAILED, PUBLISHED
}
