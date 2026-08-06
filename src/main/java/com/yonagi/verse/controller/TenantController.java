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
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    public Result<List<TenantInfoListRespDTO>> listTenants(@CurrentUser Long userId) {
        return Results.success(tenantService.listTenants(userId));
    }

    @PostMapping("/create")
    public Result<Boolean> createTenant(@CurrentUser Long userId,
                                        @RequestBody @Valid TenantCreateReqDTO requestParam) {
        return Results.success(tenantService.createTenant(userId, requestParam));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @PostMapping("/{tenantId}/update")
    public Result<Boolean> updateTenant(@CurrentUser Long userId,
                                        @PathVariable Long tenantId,
                                        @RequestBody @Valid TenantUpdateReqDTO requestParam) {
        return Results.success(tenantService.updateTenant(userId, tenantId, requestParam));
    }

    @GetMapping("/{tenantId}/info")
    public Result<TenantInfoRespDTO> getTenantInfo(@CurrentUser Long userId,
                                  @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.getTenantInfo(userId, tenantId));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    @PostMapping("/{tenantId}/disable/prepare")
    public Result<TenantClosePrepareRespDTO> prePareCloseTenant(@CurrentUser Long userId,
                                                                @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.prepareCloseTenant(userId, tenantId));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    @PostMapping("/{tenantId}/disable/confirm")
    public Result<Boolean> closeTenant(@CurrentUser Long userId,
                                       @PathVariable Long tenantId,
                                       @RequestBody @Valid TenantCloseReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.closeTenant(userId, tenantId, requestParam));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{tenantId}/invites")
    public Result<TenantInviteRespDTO> inviteUser(@CurrentUser Long userId,
                                                  @PathVariable Long tenantId,
                                                  @RequestBody TenantInviteReqDTO requestParam) {
        return Results.success(tenantService.inviteUser(userId, tenantId, requestParam));
    }

    @PostMapping("/join")
    public Result<TenantJoinRespDTO> joinTenant(@CurrentUser Long userId,
                                      @RequestBody @Valid TenantJoinReqDTO requestParam) {
        return Results.success(tenantService.joinTenant(userId, requestParam));
    }

    @PostMapping("/{tenantId}/leave/prepare")
    public Result<TenantLeavePrepareRespDTO> prepareLeaveTenant(@CurrentUser Long userId, @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.prepareLeaveTenant(userId, tenantId));
    }

    @PostMapping("/{tenantId}/leave/confirm")
    public Result<TenantLeaveRespDTO> leaveTenant(@CurrentUser Long userId, @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.leaveTenant(userId, tenantId));
    }

    @PostMapping("/{tenantId}/switch")
    public Result<TenantSwitchRespDTO> switchTenant(@CurrentUser Long userId,
                                                    @PathVariable Long tenantId) {
        return Results.success(tenantService.switchTenant(userId, tenantId));
    }

    @GetMapping("/{tenantId}/members")
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
    @PostMapping("/{tenantId}/members/{memberId}/role")
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
    @DeleteMapping("/{tenantId}/members/{memberId}/remove")
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
    @GetMapping("/{tenantId}/invites")
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
    @PostMapping("/{tenantId}/invites/{inviteCodeId}/deactivate")
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
    @PostMapping("/{tenantId}/invites/{inviteCodeId}/activate")
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

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{tenantId}/join-requests")
    public Result<TenantJoinReqListRespDTO> listJoinRequests(@CurrentUser Long userId,
                                                @PathVariable Long tenantId,
                                                @RequestParam @Valid Integer pageNum,
                                                @RequestParam Integer pageSize) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.listJoinRequests(userId, tenantId, pageNum, pageSize));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{tenantId}/join-requests/{requestId}/approve")
    public Result<Boolean> approveJoinRequest(@CurrentUser Long userId,
                                              @PathVariable Long tenantId,
                                              @PathVariable Long requestId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (requestId == null) {
            throw new ClientException(TenantErrorCodeEnum.REQUEST_ID_IS_NULL);
        }
        return Results.success(tenantService.approveJoinRequest(userId, tenantId, requestId));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{tenantId}/join-requests/{requestId}/reject")
    public Result<Boolean> rejectJoinRequest(@CurrentUser Long userId,
                                             @PathVariable Long tenantId,
                                             @PathVariable Long requestId,
                                             @RequestBody TenantJoinRejectReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (requestId == null) {
            throw new ClientException(TenantErrorCodeEnum.REQUEST_ID_IS_NULL);
        }
        return Results.success(tenantService.rejectJoinRequest(userId, tenantId, requestId, requestParam));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @GetMapping("/{tenantId}/join-requests/unreviewed-count")
    public Result<Long> getUnreviewedJoinReqCount(@CurrentUser Long userId,
                                                     @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(tenantService.getUnreviewedJoinReqCount(userId, tenantId));
    }
}
