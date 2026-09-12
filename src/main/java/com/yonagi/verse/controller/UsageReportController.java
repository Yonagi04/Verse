package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.enums.UsageExportType;
import com.yonagi.verse.common.enums.UsageReportingErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.UsageDashboardRespDTO;
import com.yonagi.verse.dto.resp.UsageReportRespDTO;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.dto.resp.UsageFilterOptionsRespDTO;
import com.yonagi.verse.service.UsageReportService;
import com.yonagi.verse.service.UsageExportService;
import jakarta.servlet.http.HttpServletResponse;
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
    private final UsageExportService exportService;

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
        @RequestParam(required=false) Long userId,
        @RequestParam(required=false) Long apiKeyId,
        @RequestParam(required=false) Long serviceId) {
        return Results.success(service.query(context,tenantId,UsageGranularity.DAY,from,to,userId,apiKeyId,serviceId).getTotal());
    }

    /** 返回按小时、日、周或月补齐空桶的时间序列。 */
    @GetMapping("/timeseries")
    public Result<UsageReportRespDTO> timeseries(@CurrentUser UserContext context, @PathVariable Long tenantId,
        @RequestParam(defaultValue="day") String granularity,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(required=false) Long userId,
        @RequestParam(required=false) Long apiKeyId,
        @RequestParam(required=false) Long serviceId) {
        try { return Results.success(service.query(context,tenantId,UsageGranularity.parse(granularity),from,to,userId,apiKeyId,serviceId)); }
        catch (IllegalArgumentException e) { throw new ClientException(UsageReportingErrorCodeEnum.GRANULARITY_INVALID); }
    }

    /** 返回按模型、API Key 或成员聚合的排行榜。 */
    @GetMapping("/breakdown")
    public Result<UsageBreakdownRespDTO> breakdown(@CurrentUser UserContext context, @PathVariable Long tenantId,
        @RequestParam(defaultValue="day") String granularity,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(required=false) Long userId,
        @RequestParam(required=false) Long apiKeyId,
        @RequestParam(required=false) Long serviceId,
        @RequestParam(defaultValue="model") String dimension,
        @RequestParam(defaultValue="totalTokens") String orderBy,
        @RequestParam(defaultValue="10") int limit) {
        return Results.success(service.breakdown(context,tenantId,parseGranularity(granularity),from,to,
                userId,apiKeyId,serviceId,parseDimension(dimension),parseOrder(orderBy),limit));
    }

    /** 返回当前用户在租户内有权选择的筛选项。 */
    @GetMapping("/filters")
    public Result<UsageFilterOptionsRespDTO> filters(@CurrentUser UserContext context, @PathVariable Long tenantId) {
        return Results.success(service.filterOptions(context,tenantId));
    }

    /** 同步导出有界的 XLSX 报表。 */
    @GetMapping("/export")
    public void export(HttpServletResponse response, @CurrentUser UserContext context, @PathVariable Long tenantId,
        @RequestParam(defaultValue="timeseries") String type,
        @RequestParam(defaultValue="day") String granularity,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(required=false) Long userId,
        @RequestParam(required=false) Long apiKeyId,
        @RequestParam(required=false) Long serviceId,
        @RequestParam(defaultValue="model") String dimension,
        @RequestParam(defaultValue="totalTokens") String orderBy,
        @RequestParam(defaultValue="100") int limit) {
        UsageExportType exportType;
        try { exportType=UsageExportType.parse(type); }
        catch (IllegalArgumentException e) { throw new ClientException(UsageReportingErrorCodeEnum.EXPORT_INVALID); }
        exportService.export(response,context,tenantId,exportType,parseGranularity(granularity),from,to,userId,apiKeyId,
                serviceId,parseDimension(dimension),parseOrder(orderBy),limit);
    }

    private UsageGranularity parseGranularity(String value) {
        try { return UsageGranularity.parse(value); }
        catch (IllegalArgumentException e) { throw new ClientException(UsageReportingErrorCodeEnum.GRANULARITY_INVALID); }
    }

    private UsageBreakdownDimension parseDimension(String value) {
        try { return UsageBreakdownDimension.parse(value); }
        catch (IllegalArgumentException e) { throw new ClientException(UsageReportingErrorCodeEnum.DIMENSION_INVALID); }
    }

    private UsageBreakdownOrder parseOrder(String value) {
        try { return UsageBreakdownOrder.parse(value); }
        catch (IllegalArgumentException e) { throw new ClientException(UsageReportingErrorCodeEnum.FILTER_INVALID); }
    }
}
