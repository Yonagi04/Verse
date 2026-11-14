package com.yonagi.verse.async.api;

import java.time.LocalDateTime;

/** 已知内部事件的投递快照；不接受 HTTP 目标输入。 */
public record DomainEventDelivery(
        /** 已校验的内部目标 Topic。 */ String destination,
        /** Remoting 延迟等级，零表示立即。 */ Integer delayLevel,
        /** 可靠发送截止。 */ LocalDateTime retryUntil) { }
