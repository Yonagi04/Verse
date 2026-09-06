package com.yonagi.verse.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 用量报表与小时投影配置。 */
@Data
@ConfigurationProperties(prefix = "verse.llm.usage-reporting")
public class UsageReportingProperties {
    /** 是否启用报表 API 与投影任务。 */ private boolean enabled = true;
    /** 近实时投影间隔，毫秒。 */ private long refreshDelayMs = 300000;
    /** 近实时重算回看小时数。 */ private int refreshLookbackHours = 2;
    /** 补偿重算天数。 */ private int compensationDays = 7;
    /** 允许查询的最大天数。 */ private int maxRangeDays = 366;
}
