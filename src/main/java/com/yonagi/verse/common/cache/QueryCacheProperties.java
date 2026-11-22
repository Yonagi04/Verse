package com.yonagi.verse.common.cache;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

/** 核心查询缓存的有界回源配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "verse.query-cache")
public class QueryCacheProperties {
    /** 写入协议的部署阶段，不作为业务授权或自动能力协商。 */
    public enum TableMode {
        /** 原全表读写。 */
        LEGACY,
        /** 新旧写保护并行，读取仍走原路径。 */
        PREPARE,
        /** 已核验全部写入者后启用显式作用域读取。 */
        ACTIVE
    }

    /** 按源表配置协议阶段；默认空映射，现有业务不自动接入。 */
    @NotNull private Map<String, @NotNull TableMode> scopeTableModes = new HashMap<>();
    /** 自动补充广播及兼容目标后的单次依赖上限。 */
    @Min(2) @Max(256) private int maxDependencies = 64;
    /** 每轮检查的动态恢复资源上限。 */
    @Min(1) @Max(1024) private int scopeRecoveryBatch = 32;
    /** 动态恢复协作式预算，仅在资源之间检查，不中断正在执行的 Redis I/O。 */
    @Min(1) private long scopeRecoveryBudgetMillis = 2000;
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

    /** @return 未配置的表沿用原协议 */
    public TableMode tableMode(String table) {
        return scopeTableModes.getOrDefault(table, TableMode.LEGACY);
    }
}
