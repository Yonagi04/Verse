package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.service.impl.TenantAdminTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantAdminTransferController {
    private final TenantAdminTransferService transferService;

    /** 身份来自认证上下文；目标成员和操作者权限在交接事务中实时校验。 */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/{tenantId}/members/{memberId}/transfer-super-admin")
    public Result<Boolean> transfer(@CurrentUser Long userId, @PathVariable Long tenantId,
                                    @PathVariable Long memberId) {
        return Results.success(transferService.transfer(userId, tenantId, memberId));
    }
}
