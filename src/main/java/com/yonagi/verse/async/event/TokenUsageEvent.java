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

    public static final String SOURCE_EXACT = "EXACT";
    public static final String SOURCE_ESTIMATED = "ESTIMATED";
    public static final String SOURCE_UNKNOWN = "UNKNOWN";

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

    /**
     * 状态：SUCCESS / ABORTED / FAIL
     */
    private String status;

    /**
     * usage来源：EXACT / ESTIMATED / UNKNOWN
     */
    private String usageSource;

    @Override
    public String eventType() {
        return EventTag.TOKEN_USAGE;
    }
}
