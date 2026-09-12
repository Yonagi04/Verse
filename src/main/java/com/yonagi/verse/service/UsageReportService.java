package com.yonagi.verse.service;

import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.UsageDashboardRespDTO;
import com.yonagi.verse.dto.resp.UsageReportRespDTO;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.dto.resp.UsageFilterOptionsRespDTO;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.service.reporting.UsageReportFilter;
import java.time.LocalDateTime;

/** 用量报表查询服务。 */
public interface UsageReportService {
    UsageReportRespDTO query(UserContext context, Long tenantId, UsageGranularity granularity,
                             LocalDateTime from, LocalDateTime to, Long userId);
    UsageReportRespDTO query(UserContext context, Long tenantId, UsageGranularity granularity,
                             LocalDateTime from, LocalDateTime to, Long userId, Long apiKeyId, Long serviceId);
    UsageDashboardRespDTO dashboard(UserContext context, Long tenantId);
    UsageBreakdownRespDTO breakdown(UserContext context, Long tenantId, UsageGranularity granularity,
        LocalDateTime from, LocalDateTime to, Long userId, Long apiKeyId, Long serviceId,
        UsageBreakdownDimension dimension, UsageBreakdownOrder order, int limit);
    UsageFilterOptionsRespDTO filterOptions(UserContext context, Long tenantId);
    UsageReportFilter resolveFilter(UserContext context, Long tenantId, UsageGranularity granularity,
        LocalDateTime from, LocalDateTime to, Long userId, Long apiKeyId, Long serviceId);
}
