package com.yonagi.verse.dto.resp;

import lombok.Data;
import java.util.List;

/** 用量报表响应。 */
@Data
public class UsageReportRespDTO {
    /** 时间粒度。 */ private String granularity;
    /** 查询起点。 */ private String from;
    /** 查询终点。 */ private String to;
    /** 报表数据更新时间。 */ private String updatedAt;
    /** 用量投影预计最大延迟分钟数。 */ private Integer dataDelayMinutes;
    /** 汇总指标。 */ private UsageMetrics total;
    /** 时间序列。 */ private List<UsagePoint> points;

    /** 用量指标，所有大整数与金额均按字符串传输。 */
    @Data public static class UsageMetrics {
        /** 输入 Token。 */ private String inputTokens;
        /** 输出 Token。 */ private String outputTokens;
        /** 总 Token。 */ private String totalTokens;
        /** 成功请求数。 */ private String requestCount;
        /** 已计算预估费用，分。 */ private String estimatedCostFen;
        /** 精确用量数。 */ private String exactUsageCount;
        /** 预估用量数。 */ private String estimatedUsageCount;
        /** 未知用量数。 */ private String unknownUsageCount;
        /** 已计算费用数。 */ private String calculatedCount;
        /** 未启用计费数。 */ private String unpricedCount;
        /** 无法计算费用数。 */ private String uncalculableCount;
        /** 不可计费数。 */ private String notChargeableCount;
    }

    /** 单个时间桶。 */
    @Data public static class UsagePoint extends UsageMetrics {
        /** 时间桶起点。 */ private String bucket;
    }
}
