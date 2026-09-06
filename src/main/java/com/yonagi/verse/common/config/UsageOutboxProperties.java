package com.yonagi.verse.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 计费用量 Outbox 的类型安全配置。 */
@Data
@ConfigurationProperties(prefix = "verse.llm.usage-outbox")
public class UsageOutboxProperties {
    /** 是否暂存并投递计费用量事件。 */
    private boolean enabled = true;
    /** Relay 每批最大事件数。 */
    private int batchSize = 100;
    /** Relay 调度间隔，单位毫秒。 */
    private long relayDelayMs = 1000;
    /** 单批声明的租约时长，单位毫秒。 */
    private long claimLeaseMs = 30000;
    /** 自动发布最大尝试次数。 */
    private int maxAttempts = 8;
    /** 首次重试退避，单位毫秒。 */
    private long initialBackoffMs = 1000;
    /** 最大重试退避，单位毫秒。 */
    private long maxBackoffMs = 300000;
    /** 已对账发布记录保留天数。 */
    private int retentionDays = 30;
    /** 清理任务调度间隔，单位毫秒。 */
    private long cleanupDelayMs = 3600000;
}
