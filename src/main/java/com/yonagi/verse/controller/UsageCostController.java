package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.UsageCostBreakdownRespDTO;
import com.yonagi.verse.dto.resp.UsageCostSummaryRespDTO;
import com.yonagi.verse.dto.resp.UsageCostTimeseriesRespDTO;
import com.yonagi.verse.service.UsageCostQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/usage/{tenantId}/cost")
@RequiredArgsConstructor
public class UsageCostController {

    private final UsageCostQueryService queryService;

    @GetMapping("/summary")
    public Result<UsageCostSummaryRespDTO> summary(
            @CurrentUser UserContext user,
            @PathVariable Long tenantId,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) Long apiKeyId) {
        return Results.success(queryService.summary(user, tenantId, from, to, serviceId, apiKeyId));
    }

    @GetMapping("/timeseries")
    public Result<UsageCostTimeseriesRespDTO> timeseries(
            @CurrentUser UserContext user,
            @PathVariable Long tenantId,
            @RequestParam String granularity,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) Long apiKeyId) {
        return Results.success(queryService.timeseries(user, tenantId, granularity, from, to, serviceId, apiKeyId));
    }

    @GetMapping("/breakdown")
    public Result<UsageCostBreakdownRespDTO> breakdown(
            @CurrentUser UserContext user,
            @PathVariable Long tenantId,
            @RequestParam String dimension,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) Long apiKeyId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(defaultValue = "estimatedCostFen") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        return Results.success(queryService.breakdown(
                user, tenantId, dimension, from, to, serviceId, apiKeyId,
                pageNum, pageSize, sortBy, sortOrder
        ));
    }
}
