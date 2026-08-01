package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.TenantClosePrepareRespDTO;
import com.yonagi.verse.dto.resp.TenantInfoListRespDTO;
import com.yonagi.verse.dto.resp.TenantInviteRespDTO;
import com.yonagi.verse.dto.resp.TenantSwitchRespDTO;
import com.yonagi.verse.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    @PostMapping("/api/v1/tenants/{tenantId}/update")
    public Result<Boolean> updateTenant(@CurrentUser Long userId,
                                        @PathVariable Long tenantId,
                                        @RequestBody @Valid TenantUpdateReqDTO requestParam) {
        return Results.success(tenantService.updateTenant(userId, tenantId, requestParam));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    @PostMapping("/api/v1/tenants/{tenantId}/disable/prepare")
    public Result<TenantClosePrepareRespDTO> prePareCloseTenant(@CurrentUser Long userId,
                                                                @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.prepareCloseTenant(userId, tenantId));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    @PostMapping("/api/v1/tenants/{tenantId}/disable/confirm")
    public Result<Boolean> closeTenant(@CurrentUser Long userId,
                                       @PathVariable Long tenantId,
                                       @RequestBody @Valid TenantCloseReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.closeTenant(userId, tenantId, requestParam));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/api/v1/tenants/{tenantId}/invites")
    public Result<TenantInviteRespDTO> inviteUser(@CurrentUser Long userId,
                                                  @PathVariable Long tenantId,
                                                  @RequestBody TenantInviteReqDTO requestParam) {
        return Results.success(tenantService.inviteUser(userId, tenantId, requestParam));
    }

    @PostMapping("/api/v1/tenants/join")
    public Result<Boolean> joinTenant(@CurrentUser Long userId,
                                      @RequestBody @Valid TenantJoinReqDTO requestParam) {
        return Results.success(tenantService.joinTenant(userId, requestParam));
    }

    @PostMapping("/api/v1/tenants/{tenantId}/switch")
    public Result<TenantSwitchRespDTO> switchTenant(@CurrentUser Long userId,
                                                    @PathVariable Long tenantId) {
        return Results.success(tenantService.switchTenant(userId, tenantId));
    }
}
