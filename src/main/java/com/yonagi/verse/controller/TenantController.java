package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.TenantCreateReqDTO;
import com.yonagi.verse.dto.resp.TenantInfoListRespDTO;
import com.yonagi.verse.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/19 13:55
 */
@RestController
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping("/api/v1/tenants")
    public Result<List<TenantInfoListRespDTO>> listTenants(@CurrentUser Long userId) {
        return Results.success(tenantService.listTenants(userId));
    }

    @PostMapping("/api/v1/tenants/create")
    public Result<Boolean> createTenant(@CurrentUser Long userId,
                                        @RequestBody @Valid TenantCreateReqDTO requestParam) {
        return Results.success(tenantService.createTenant(userId, requestParam));
    }
}
