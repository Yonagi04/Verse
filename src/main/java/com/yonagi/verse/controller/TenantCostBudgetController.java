package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.*;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.req.TenantSettingsSaveReqDTO;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.budget.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/tenants/{tenantId}")
public class TenantCostBudgetController {
    private final TenantCostBudgetConfigurationService budgets;
    private final CostNotificationReadinessService readiness;
    @PostMapping("/settings/save")
    public Result<TenantSettingsSaveRespDTO> save(@PathVariable Long tenantId, @Valid @RequestBody TenantSettingsSaveReqDTO request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Results.success(budgets.save(UserContextHolder.get().getUserId(), tenantId, request));
    }
    @GetMapping("/cost-status")
    public Result<TenantCostStatusRespDTO> status(@PathVariable Long tenantId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Results.success(budgets.status(UserContextHolder.get().getUserId(), tenantId));
    }
    @GetMapping("/cost-notification-readiness")
    public Result<CostNotificationReadinessRespDTO> readiness(@PathVariable Long tenantId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Results.success(readiness.tenantReadiness(UserContextHolder.get().getUserId(), tenantId));
    }
}
