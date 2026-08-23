package com.yonagi.verse.async.event;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Token 消耗事件 — 转发成功后由生产者投递，消费者异步落 t_token_usage。
 *
 * @author Yonagi
 */
@Getter
@Setter
@NoArgsConstructor
public class TokenUsageEvent extends DomainEvent {

    private Long userId;

    private Long tenantId;

    private Long apiKeyId;

    private Long serviceId;

    /**
     * 实际调用的模型别名
     */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private String requestId;

    @Override
    public String eventType() {
        return EventTag.TOKEN_USAGE;
    }
}
