package com.yonagi.verse.service.reporting;

import com.yonagi.verse.common.enums.UsageGranularity;
import java.time.LocalDateTime;

/** 已完成权限与租户归属校验的用量查询条件。 */
public record UsageReportFilter(Long tenantId, Long userId, Long apiKeyId, Long serviceId,
                                LocalDateTime from, LocalDateTime to, UsageGranularity granularity,
                                boolean canReadAll) { }
