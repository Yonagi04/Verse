package com.yonagi.verse.service;

import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.UsageDashboardRespDTO;
import com.yonagi.verse.dto.resp.UsageReportRespDTO;
import java.time.LocalDateTime;

/** 用量报表查询服务。 */
public interface UsageReportService {
    UsageReportRespDTO query(UserContext context, Long tenantId, UsageGranularity granularity,
                             LocalDateTime from, LocalDateTime to, Long userId);
    UsageDashboardRespDTO dashboard(UserContext context, Long tenantId);
}
