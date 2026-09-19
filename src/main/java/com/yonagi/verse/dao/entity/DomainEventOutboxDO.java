package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 通用可靠领域事件暂存记录。 */
@Data
@TableName("t_domain_event_outbox")
public class DomainEventOutboxDO {
    /** 自增主键。 */ private Long id;
    /** 稳定事件 ID。 */ private String eventId;
    /** 租户 ID。 */ private Long tenantId;
    /** RocketMQ 标签。 */ private String eventType;
    /** 业务顺序键。 */ private String messageKey;
    /** 不可变事件 JSON。 */ private String payloadJson;
    /** Outbox 状态。 */ private String status;
    /** 发送尝试次数。 */ private Integer attemptCount;
    /** 下次重试时间。 */ private LocalDateTime nextRetryAt;
    /** 声明实例。 */ private String claimOwner;
    /** 声明到期时间。 */ private LocalDateTime claimExpiresAt;
    /** 最近错误摘要。 */ private String lastError;
    /** Broker 接收时间。 */ private LocalDateTime publishedAt;
    /** 事实对账时间。 */ private LocalDateTime reconciledAt;
    /** 创建时间。 */ private LocalDateTime createTime;
    /** 更新时间。 */ private LocalDateTime updateTime;
}
