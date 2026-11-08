package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.req.CostReconciliationResolveReqDTO;
import com.yonagi.verse.dto.resp.CostReconciliationRespDTO;
import com.yonagi.verse.service.budget.CostReconciliationQueries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cost-reconciliation-batches/{batchCode}/cases")
public class CostReconciliationController {
    private final CostReconciliationQueries queries;

    @GetMapping
    public Result<CostReconciliationRespDTO.Batch> page(@PathVariable String batchCode,
            @RequestParam(defaultValue = "PENDING") CostReconciliationQueries.State state,
            @RequestParam(required = false) @Positive Long after) {
        return Results.success(queries.page(UserContextHolder.get().getUserId(), batchCode, state, after));
    }

    @GetMapping("/{caseId}")
    public Result<CostReconciliationRespDTO.Case> detail(@PathVariable String batchCode, @PathVariable @Positive Long caseId) {
        return Results.success(queries.detail(UserContextHolder.get().getUserId(), batchCode, caseId));
    }

    @PostMapping("/{caseId}/resolve")
    public Result<CostReconciliationRespDTO.Case> resolve(@PathVariable String batchCode, @PathVariable @Positive Long caseId,
                                                         @Valid @RequestBody CostReconciliationResolveReqDTO request) {
        return Results.success(queries.resolve(UserContextHolder.get().getUserId(), batchCode, caseId, request));
    }
}
