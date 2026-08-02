package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
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

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @PostMapping("/api/v1/tenants/{tenantId}/update")
    public Result<Boolean> updateTenant(@CurrentUser Long userId,
                                        @PathVariable Long tenantId,
                                        @RequestBody @Valid TenantUpdateReqDTO requestParam) {
        return Results.success(tenantService.updateTenant(userId, tenantId, requestParam));
    }

    @GetMapping("/api/v1/tenants/{tenantId}/info")
    public Result<TenantInfoRespDTO> getTenantInfo(@CurrentUser Long userId,
                                  @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.getTenantInfo(userId, tenantId));
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

    @PostMapping("/api/v1/tenants/{tenantId}/leave/prepare")
    public Result<TenantLeavePrepareRespDTO> prepareLeaveTenant(@CurrentUser Long userId, @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.prepareLeaveTenant(userId, tenantId));
    }

    @PostMapping("/api/v1/tenants/{tenantId}/leave/confirm")
    public Result<Boolean> leaveTenant(@CurrentUser Long userId, @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.leaveTenant(userId, tenantId));
    }

    @PostMapping("/api/v1/tenants/{tenantId}/switch")
    public Result<TenantSwitchRespDTO> switchTenant(@CurrentUser Long userId,
                                                    @PathVariable Long tenantId) {
        return Results.success(tenantService.switchTenant(userId, tenantId));
    }

    @GetMapping("/api/v1/tenants/{tenantId}/members")
    public Result<TenantMembersListRespDTO> listTenantMembers(@CurrentUser Long userId,
                                                              @PathVariable Long tenantId,
                                                              @RequestParam @Valid Integer pageNum,
                                                              @RequestParam @Valid Integer pageSize) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.listTenantMembers(userId, tenantId, pageNum, pageSize));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/api/v1/tenants/{tenantId}/members/{memberId}/role")
    public Result<Boolean> updateMemberRole(@CurrentUser Long userId,
                                            @PathVariable Long tenantId,
                                            @PathVariable Long memberId,
                                            @RequestBody @Valid TenantMemberRoleUpdateReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        if (memberId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_ID_IS_NULL);
        }
        if (userId.equals(memberId)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_UPDATE_ID_SAME);
        }
        if (!RoleEnum.isValidRole(requestParam.getNewRole())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_ROLE_ERROR);
        }
        return Results.success(tenantService.updateMemberRole(userId, tenantId, memberId, requestParam));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/api/v1/tenants/{tenantId}/members/{memberId}/remove")
    public Result<Boolean> removeMember(@CurrentUser Long userId,
                                        @PathVariable Long tenantId,
                                        @PathVariable Long memberId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        if (memberId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_ID_IS_NULL);
        }
        if (userId.equals(memberId)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_REMOVE_ID_SAME);
        }
        return Results.success(tenantService.removeMember(userId, tenantId, memberId));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/api/v1/tenants/{tenantId}/invites")
    public Result<TenantInviteListRespDTO> listTenantInviteCodes(@CurrentUser Long userId,
                                                                       @PathVariable Long tenantId,
                                                                       @RequestParam @Valid Integer pageNum,
                                                                       @RequestParam @Valid Integer pageSize) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.listTenantInviteCodes(userId, tenantId, pageNum, pageSize));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/api/v1/tenants/{tenantId}/invites/{inviteCodeId}/deactivate")
    public Result<Boolean> deactivateInviteCode(@CurrentUser Long userId,
                                              @PathVariable Long tenantId,
                                              @PathVariable Long inviteCodeId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (inviteCodeId == null) {
            throw new ClientException(TenantErrorCodeEnum.INVITE_CODE_IS_NULL);
        }
        return Results.success(tenantService.deactivateInviteCode(userId, tenantId, inviteCodeId));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/api/v1/tenants/{tenantId}/invites/{inviteCodeId}/activate")
    public Result<Boolean> activateInviteCode(@CurrentUser Long userId,
                                              @PathVariable Long tenantId,
                                              @PathVariable Long inviteCodeId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (inviteCodeId == null) {
            throw new ClientException(TenantErrorCodeEnum.INVITE_CODE_IS_NULL);
        }
        return Results.success(tenantService.activateInviteCode(userId, tenantId, inviteCodeId));
    }
}
