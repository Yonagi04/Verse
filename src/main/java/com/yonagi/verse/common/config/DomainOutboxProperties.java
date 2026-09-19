package com.yonagi.verse.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 通用可靠事件 Outbox 配置。 */
@Data
@ConfigurationProperties(prefix = "verse.async.domain-outbox")
public class DomainOutboxProperties {
    /** 是否启用 Relay；关闭后仍允许业务暂存事件。 */
    private boolean relayEnabled = true;
    /** 单批声明数量。 */
    private int batchSize = 100;
    /** Relay 调度间隔，毫秒。 */
    private long relayDelayMs = 1000;
    /** 声明租约，毫秒。 */
    private long claimLeaseMs = 30000;
    /** 最大发送次数。 */
    private int maxAttempts = 8;
    /** 初始退避，毫秒。 */
    private long initialBackoffMs = 1000;
    /** 最大退避，毫秒。 */
    private long maxBackoffMs = 300000;
    /** 未对账告警阈值，毫秒。 */
    private long reconciliationThresholdMs = 300000;
    /** 已对账 Outbox 保留天数。 */
    private int reconciledRetentionDays = 30;
    /** 对账清理调度间隔，毫秒。 */
    private long maintenanceDelayMs = 60000;
    /** 动态详情 JSON 最大 UTF-8 字节数。 */
    private int detailsMaxBytes = 8192;
}
