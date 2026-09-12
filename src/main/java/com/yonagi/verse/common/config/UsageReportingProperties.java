package com.yonagi.verse.common.config;

import lombok.Data;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 用量报表与小时投影配置。 */
@Data
@Validated
@ConfigurationProperties(prefix = "verse.llm.usage-reporting")
public class UsageReportingProperties {
    /** 是否启用报表 API 与投影任务。 */ private boolean enabled = true;
    /** 近实时投影间隔，毫秒。 */ @Min(1000) private long refreshDelayMs = 300000;
    /** 近实时重算回看小时数。 */ @Min(1) private int refreshLookbackHours = 2;
    /** 补偿重算天数。 */ @Min(1) private int compensationDays = 7;
    /** 允许查询的最大天数。 */ @Min(1) private int maxRangeDays = 366;
    /** 原始导出最大天数。 */ @Min(1) private int rawExportMaxRangeDays = 31;
    /** 原始导出最大行数。 */ @Min(1) private int exportMaxRows = 100000;
    /** 原始导出查询批大小。 */ @Min(1) private int exportBatchSize = 1000;
    /** 单实例并发导出数。 */ @Min(1) private int exportMaxConcurrency = 2;
}
