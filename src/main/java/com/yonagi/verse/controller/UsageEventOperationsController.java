package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.resp.UsageEventReconciliationRespDTO;
import com.yonagi.verse.service.UsageEventOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 计费用量事件运维接口，不提供费用报表或聚合。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/usage-events")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class UsageEventOperationsController {

    private final UsageEventOperationsService operationsService;

    @GetMapping("/{tenantId}/reconciliation")
    public Result<UsageEventReconciliationRespDTO> reconcile(@CurrentUser Long userId,
                                                              @PathVariable Long tenantId) {
        return Results.success(operationsService.reconcile(userId, tenantId));
    }

    @PostMapping("/{tenantId}/{eventId}/replay")
    public Result<Boolean> replay(@CurrentUser Long userId, @PathVariable Long tenantId,
                                  @PathVariable String eventId) {
        return Results.success(operationsService.replay(userId, tenantId, eventId));
    }
}
