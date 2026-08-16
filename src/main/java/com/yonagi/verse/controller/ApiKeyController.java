package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.ApiKeyErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.ApiKeyCreateReqDTO;
import com.yonagi.verse.dto.resp.ApiKeyListRespDTO;
import com.yonagi.verse.dto.resp.ApiKeyRespDTO;
import com.yonagi.verse.service.ApiKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API Key 管理
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/08/16
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @PostMapping("/{tenantId}/api-keys")
    public Result<ApiKeyRespDTO> createApiKey(@CurrentUser Long userId,
                                              @PathVariable Long tenantId,
                                              @RequestBody @Valid ApiKeyCreateReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(apiKeyService.createApiKey(userId, tenantId, requestParam));
    }

    @GetMapping("/{tenantId}/api-keys")
    public Result<List<ApiKeyListRespDTO>> listApiKeys(@CurrentUser Long userId,
                                                       @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(apiKeyService.listApiKeys(userId, tenantId));
    }

    @DeleteMapping("/{tenantId}/api-keys/{keyId}")
    public Result<Boolean> revokeApiKey(@CurrentUser Long userId,
                                        @PathVariable Long tenantId,
                                        @PathVariable Long keyId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        if (keyId == null) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_ID_IS_NULL);
        }
        return Results.success(apiKeyService.revokeApiKey(userId, tenantId, keyId));
    }
}
