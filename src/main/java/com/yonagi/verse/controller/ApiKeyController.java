package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.ApiKeyErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.ApiKeyCreateReqDTO;
import com.yonagi.verse.dto.req.ApiKeyRevokeReqDTO;
import com.yonagi.verse.dto.req.ApiKeyUpdateReqDTO;
import com.yonagi.verse.dto.resp.ApiKeyPageRespDTO;
import com.yonagi.verse.dto.resp.ApiKeyRespDTO;
import com.yonagi.verse.service.ApiKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * API Key 管理
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/08/16
 */
@RestController
@RequestMapping("/api/v1/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @PostMapping("/{tenantId}/create")
    public Result<ApiKeyRespDTO> createApiKey(@CurrentUser Long userId,
                                              @PathVariable Long tenantId,
                                              @RequestBody @Valid ApiKeyCreateReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(apiKeyService.createApiKey(userId, tenantId, requestParam));
    }

    @GetMapping("/{tenantId}/list")
    public Result<ApiKeyPageRespDTO> listApiKeys(@CurrentUser Long userId,
                                                 @PathVariable Long tenantId,
                                                 @RequestParam Integer pageNum,
                                                 @RequestParam Integer pageSize) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(apiKeyService.listApiKeys(userId, tenantId, pageNum, pageSize));
    }

    @DeleteMapping("/{tenantId}/delete")
    public Result<Boolean> revokeApiKey(@CurrentUser Long userId,
                                        @PathVariable Long tenantId,
                                        @RequestBody @Valid ApiKeyRevokeReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        Long keyId;
        try {
            keyId = Long.parseLong(requestParam.getApiKeyId());
        } catch (Exception e) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_NOT_EXIST);
        }
        return Results.success(apiKeyService.revokeApiKey(userId, tenantId, keyId));
    }

    @PostMapping("/{tenantId}/update")
    public Result<Boolean> updateApiKey(@CurrentUser Long userId,
                                        @PathVariable Long tenantId,
                                        @RequestParam Long apiKeyId,
                                        @RequestBody @Valid ApiKeyUpdateReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(apiKeyService.updateApiKey(userId, tenantId, apiKeyId, requestParam));
    }
}
