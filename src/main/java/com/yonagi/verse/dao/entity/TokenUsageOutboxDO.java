package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 计费用量事件持久化发送记录。 */
@Data
@TableName("t_token_usage_outbox")
public class TokenUsageOutboxDO {
    /** 自增主键。 */
    private Long id;
    /** 稳定事件 ID。 */
    private String eventId;
    /** 租户 ID。 */
    private Long tenantId;
    /** RocketMQ 标签。 */
    private String eventType;
    /** RocketMQ 顺序键。 */
    private String messageKey;
    /** 完整不可变事件 JSON。 */
    private String payloadJson;
    /** PENDING/CLAIMED/RETRY/FAILED/PUBLISHED。 */
    private String status;
    /** 已执行发送尝试次数。 */
    private Integer attemptCount;
    /** 下次允许重试时间。 */
    private LocalDateTime nextRetryAt;
    /** 当前声明实例。 */
    private String claimOwner;
    /** 当前声明过期时间。 */
    private LocalDateTime claimExpiresAt;
    /** 最近一次发送错误。 */
    private String lastError;
    /** Broker 确认接收时间。 */
    private LocalDateTime publishedAt;
    /** 已确认事实表存在对应 event_id 的时间。 */
    private LocalDateTime reconciledAt;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}
