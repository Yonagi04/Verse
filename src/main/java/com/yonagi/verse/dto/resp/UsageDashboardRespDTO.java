package com.yonagi.verse.dto.resp;

import lombok.Data;

/** Dashboard 用量组合响应。 */
@Data
public class UsageDashboardRespDTO {
    /** 今日汇总。 */ private UsageReportRespDTO.UsageMetrics today;
    /** 最近二十四小时序列。 */ private UsageReportRespDTO recent24Hours;
    /** 最近七天序列。 */ private UsageReportRespDTO recent7Days;
    /** 数据更新时间。 */ private String updatedAt;
    /** 用量投影预计最大延迟分钟数。 */ private Integer dataDelayMinutes;
}
