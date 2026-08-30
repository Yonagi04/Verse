package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.LlmAuditErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.LlmAuditDetailRespDTO;
import com.yonagi.verse.dto.resp.LlmAuditListRespDTO;
import com.yonagi.verse.service.LlmAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 调用审计查询接口 — 列表 + 详情，按角色隔离。
 *
 * @author Yonagi
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class LlmAuditController {

    private final LlmAuditService llmAuditService;

    @GetMapping("/{tenantId}/list")
    public Result<LlmAuditListRespDTO> listAudit(@CurrentUser UserContext ctx,
                                                 @PathVariable Long tenantId,
                                                 @RequestParam Integer pageNum,
                                                 @RequestParam Integer pageSize,
                                                 @RequestParam(required = false) Long userId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        if (pageNum == null) {
            pageNum = 1;
        }
        if (pageSize == null) {
            pageSize = 10;
        }
        if (pageNum < 1 || pageSize < 1) {
            throw new ClientException(LlmAuditErrorCodeEnum.AUDIT_PAGINATION_PARAM_INVALID);
        }
        return Results.success(llmAuditService.listAudit(ctx, tenantId, pageNum, pageSize, userId));
    }

    @GetMapping("/{tenantId}/detail/{auditId}")
    public Result<LlmAuditDetailRespDTO> getAuditDetail(@CurrentUser UserContext ctx,
                                                        @PathVariable Long tenantId,
                                                        @PathVariable Long auditId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(llmAuditService.getAuditDetail(ctx, tenantId, auditId));
    }
}
