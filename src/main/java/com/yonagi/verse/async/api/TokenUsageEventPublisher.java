package com.yonagi.verse.async.api;

import com.yonagi.verse.async.event.TokenUsageEvent;

/** 计费用量事件的持久化发布入口。 */
public interface TokenUsageEventPublisher {
    /** 在独立事务中持久化事件，成功返回即表示本地发送保障已经建立。 */
    void publish(TokenUsageEvent event);
}
