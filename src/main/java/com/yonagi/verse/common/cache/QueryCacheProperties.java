package com.yonagi.verse.common.cache;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 核心查询缓存的有界回源配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "verse.query-cache")
public class QueryCacheProperties {
    /** 分布式锁及写栅栏最长等待毫秒数。 */
    @Min(100) private long waitMillis = 3000;
    /** 单实例并发回源及命中授权校验上限。 */
    @Min(1) private int maxConcurrency = 8;
    /** 不存在和空结果的缓存秒数，至少十分钟，新增数据同步失效。 */
    @Min(600) private int negativeSeconds = (int) QueryCacheTtl.MINUTES_10;
    /** 大缓存值的监控告警阈值，超过阈值仍保存结果。 */
    @Min(1024) private int largeValueBytes = 1048576;
    /** 单表每轮重试的已结束 Token 上限。 */
    @Min(1) private int fenceRetryBatch = 32;
    /** 自动恢复扫描间隔毫秒数，不是写栅栏租约。 */
    @Min(1000) private long fenceRetryDelayMillis = 5000;
    /** Redis 无法保存结束记录时，单实例保留的确认记录上限。 */
    @Min(1) private int fencePendingLimit = 512;
    /** 每次失效最多清理的索引批数，每批至多 200 个键。 */
    @Min(1) private int fenceCleanupBatches = 5;
}
