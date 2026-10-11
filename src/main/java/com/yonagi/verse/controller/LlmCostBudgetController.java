package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.*;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.LlmManageService;
import com.yonagi.verse.service.budget.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 当前租户的模型管理新入口，旧管理路径继续委托同一服务。 */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/llm-services")
public class LlmCostBudgetController {
    private final LlmManageService models;
    private final LlmCostBudgetConfigurationService budgets;
    private final CostNotificationReadinessService readiness;
    @PostMapping("/create") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public Result<Boolean> create(@RequestBody @Valid LlmServiceAddReqDTO request) {
        var actor=UserContextHolder.get(); return Results.success(models.addLlmService(actor.getUserId(),actor.getCurrentTenantId(),request));
    }
    @PostMapping("/{serviceId}/update") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public Result<Boolean> update(@PathVariable Long serviceId,@RequestBody @Valid LlmServiceUpdateReqDTO request) {
        var actor=UserContextHolder.get(); return Results.success(models.updateLlmService(actor.getUserId(),actor.getCurrentTenantId(),serviceId,request));
    }
    @PostMapping("/{serviceId}/cost-budget/update") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public Result<LlmCostStatusRespDTO> updateBudget(@PathVariable Long serviceId,@RequestBody @Valid LlmCostBudgetUpdateReqDTO request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); var actor=UserContextHolder.get();
        return Results.success(budgets.update(actor.getUserId(),actor.getCurrentTenantId(),serviceId,request));
    }
    @GetMapping("/{serviceId}/cost-status")
    public Result<LlmCostStatusRespDTO> status(@PathVariable Long serviceId,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); var actor=UserContextHolder.get();
        return Results.success(budgets.status(actor.getUserId(),actor.getCurrentTenantId(),serviceId));
    }
    @GetMapping("/cost-status")
    public Result<LlmCostStatusRespDTO.Batch> statuses(@RequestParam List<Long> serviceIds,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); var actor=UserContextHolder.get();
        return Results.success(budgets.statuses(actor.getUserId(),actor.getCurrentTenantId(),serviceIds));
    }
    @GetMapping("/cost-notification-readiness") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public Result<CostNotificationReadinessRespDTO> readiness(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); var actor=UserContextHolder.get();
        return Results.success(readiness.readiness(actor.getUserId(),actor.getCurrentTenantId()));
    }
}
