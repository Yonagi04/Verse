package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Token 用量小时预聚合实体。 */
@Data
@TableName("t_token_usage_hourly_agg")
public class TokenUsageHourlyAggDO {
    /** 自增主键。 */ private Long id;
    /** 租户业务 ID。 */ private Long tenantId;
    /** 用户业务 ID。 */ private Long userId;
    /** API Key 业务 ID。 */ private Long apiKeyId;
    /** LLM 服务业务 ID。 */ private Long serviceId;
    /** 实际模型名快照。 */ private String model;
    /** 上海时区小时桶起点。 */ private LocalDateTime bucketStart;
    /** 输入 Token 合计。 */ private Long inputTokens;
    /** 缓存命中输入 Token 合计。 */ private Long cachedInputTokens;
    /** 缓存写入输入 Token 合计。 */ private Long cacheWriteInputTokens;
    /** 输出 Token 合计。 */ private Long outputTokens;
    /** 总 Token 合计。 */ private Long totalTokens;
    /** 终态请求数。 */ private Long requestCount;
    /** 成功请求数。 */ private Long successRequestCount;
    /** 精确用量请求数。 */ private Long exactUsageCount;
    /** 预估用量请求数。 */ private Long estimatedUsageCount;
    /** 未知用量请求数。 */ private Long unknownUsageCount;
    /** 可计算预估费用合计，分。 */ private BigDecimal estimatedCostFen;
    /** 费用已计算请求数。 */ private Long calculatedCount;
    /** 未启用计费请求数。 */ private Long unpricedCount;
    /** 费用无法计算请求数。 */ private Long uncalculableCount;
    /** 不可计费请求数。 */ private Long notChargeableCount;
    /** 创建时间。 */ private LocalDateTime createTime;
    /** 更新时间。 */ private LocalDateTime updateTime;
}
