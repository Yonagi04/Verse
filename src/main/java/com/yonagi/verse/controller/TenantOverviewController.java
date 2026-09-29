package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.common.security.TenantContextExempt;
import com.yonagi.verse.dto.req.TenantPreferenceReqDTO;
import com.yonagi.verse.dto.resp.TenantOverviewRespDTO;
import com.yonagi.verse.dto.resp.TenantPreferenceRespDTO;
import com.yonagi.verse.service.TenantOverviewService;
import com.yonagi.verse.service.TenantPreferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 跨当前租户的本人已加入租户只读摘要及本人偏好。 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantOverviewController {
    private final TenantOverviewService overviewService;
    private final TenantPreferenceService preferenceService;

    @GetMapping("/overview")
    public Result<TenantOverviewRespDTO.Batch> batch(@CurrentUser Long userId) {
        return Results.success(overviewService.batch(userId));
    }

    @GetMapping("/{tenantId}/overview")
    @TenantContextExempt(reason = "仅向目标有效成员返回按目标角色裁剪的只读摘要，不改变当前租户")
    public Result<TenantOverviewRespDTO.Detail> detail(@CurrentUser Long userId,
            @PathVariable Long tenantId) {
        return Results.success(overviewService.detail(userId, tenantId));
    }

    @PutMapping("/{tenantId}/preference")
    @TenantContextExempt(reason = "用户可以收藏非当前的已加入租户，只修改本人的成员关系偏好")
    public Result<TenantPreferenceRespDTO> updatePreference(@CurrentUser Long userId,
            @PathVariable Long tenantId, @RequestBody @Valid TenantPreferenceReqDTO request) {
        return Results.success(preferenceService.update(userId, tenantId, request));
    }
}
