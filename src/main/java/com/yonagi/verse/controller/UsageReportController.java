package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.enums.UsageReportingErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.UsageDashboardRespDTO;
import com.yonagi.verse.dto.resp.UsageReportRespDTO;
import com.yonagi.verse.service.UsageReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

/** Token 用量报表 HTTP API。 */
@RestController
@RequestMapping("/api/v1/usage/{tenantId}")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "verse.llm.usage-reporting", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UsageReportController {
    private final UsageReportService service;

    /** 返回 dashboard 最近二十四小时和七天关键图表。 */
    @GetMapping("/dashboard")
    public Result<UsageDashboardRespDTO> dashboard(@CurrentUser UserContext context, @PathVariable Long tenantId) {
        return Results.success(service.dashboard(context, tenantId));
    }

    /** 返回指定范围汇总。 */
    @GetMapping("/overview")
    public Result<UsageReportRespDTO.UsageMetrics> overview(@CurrentUser UserContext context, @PathVariable Long tenantId,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(required=false) Long userId) {
        return Results.success(service.query(context,tenantId,UsageGranularity.DAY,from,to,userId).getTotal());
    }

    /** 返回按小时、日、周或月补齐空桶的时间序列。 */
    @GetMapping("/timeseries")
    public Result<UsageReportRespDTO> timeseries(@CurrentUser UserContext context, @PathVariable Long tenantId,
        @RequestParam(defaultValue="day") String granularity,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(required=false) Long userId) {
        try { return Results.success(service.query(context,tenantId,UsageGranularity.parse(granularity),from,to,userId)); }
        catch (IllegalArgumentException e) { throw new ClientException(UsageReportingErrorCodeEnum.GRANULARITY_INVALID); }
    }
}
