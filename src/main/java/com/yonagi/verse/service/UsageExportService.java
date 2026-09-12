package com.yonagi.verse.service;

import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.enums.UsageExportType;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.security.UserContext;
import jakarta.servlet.http.HttpServletResponse;

import java.time.LocalDateTime;

/** 用量 Excel 报表导出服务。 */
public interface UsageExportService {
    /** 将指定类型的报表流式写入 HTTP 响应。 */
    void export(HttpServletResponse response, UserContext context, Long tenantId, UsageExportType type,
                UsageGranularity granularity, LocalDateTime from, LocalDateTime to, Long userId,
                Long apiKeyId, Long serviceId, UsageBreakdownDimension dimension,
                UsageBreakdownOrder orderBy, int limit);
}
