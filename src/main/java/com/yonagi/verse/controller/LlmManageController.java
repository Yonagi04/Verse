package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.LlmManageErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.LlmServiceAddReqDTO;
import com.yonagi.verse.dto.req.LlmServiceRemoveReqDTO;
import com.yonagi.verse.dto.req.LlmServiceUpdateReqDTO;
import com.yonagi.verse.dto.resp.LlmServiceRemovePreRespDTO;
import com.yonagi.verse.dto.resp.LlmServiceInfoRespDTO;
import com.yonagi.verse.dto.resp.LlmServiceListRespDTO;
import com.yonagi.verse.service.LlmManageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/22 10:54
 */
@RestController
@RequestMapping("/api/v1/llm-service")
@RequiredArgsConstructor
public class LlmManageController {

    private final LlmManageService llmManageService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{tenantId}/add")
    public Result<Boolean> addLlmService(@CurrentUser Long userId,
                                         @PathVariable Long tenantId,
                                         @RequestBody @Valid LlmServiceAddReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(llmManageService.addLlmService(userId, tenantId, requestParam));
    }

    @GetMapping("/{tenantId}/list")
    public Result<LlmServiceListRespDTO> listLlmService(@CurrentUser Long userId,
                                                        @PathVariable Long tenantId,
                                                        @RequestParam Integer pageNum,
                                                        @RequestParam Integer pageSize) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        if (pageNum == null) {
            pageNum = 1;
        }
        if (pageSize == null) {
            pageSize = 5;
        }
        if (pageNum < 1 || pageSize < 1) {
            throw new ClientException(LlmManageErrorCodeEnum.PAGINATION_PARAM_INVALID);
        }
        return Results.success(llmManageService.listLlmService(userId, tenantId, pageNum, pageSize));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{tenantId}/update/{serviceId}")
    public Result<Boolean> updateLlmService(@CurrentUser Long userId,
                                            @PathVariable Long tenantId,
                                            @PathVariable Long serviceId,
                                            @RequestBody @Valid LlmServiceUpdateReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (serviceId == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_ID_IS_NULL);
        }
        return Results.success(llmManageService.updateLlmService(userId, tenantId, serviceId, requestParam));
    }

    @GetMapping("/{tenantId}/info/{serviceId}")
    public Result<LlmServiceInfoRespDTO> getLlmInfo(@CurrentUser Long userId,
                                                    @PathVariable Long tenantId,
                                                    @PathVariable Long serviceId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (serviceId == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_ID_IS_NULL);
        }
        return Results.success(llmManageService.getLlmInfo(userId, tenantId, serviceId));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @PostMapping("/{tenantId}/disable/{serviceId}")
    public Result<Boolean> disableLlmService(@CurrentUser Long userId,
                                             @PathVariable Long tenantId,
                                             @PathVariable Long serviceId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (serviceId == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_ID_IS_NULL);
        }
        return Results.success(llmManageService.disableLlmService(userId, tenantId, serviceId));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @PostMapping("/{tenantId}/enable/{serviceId}")
    public Result<Boolean> enableLlmService(@CurrentUser Long userId,
                                              @PathVariable Long tenantId,
                                              @PathVariable Long serviceId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (serviceId == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_ID_IS_NULL);
        }
        return Results.success(llmManageService.enableLlmService(userId, tenantId, serviceId));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @PostMapping("/{tenantId}/remove/prepare/{serviceId}")
    public Result<LlmServiceRemovePreRespDTO> prepareRemoveLlmService(@CurrentUser Long userId,
                                                                      @PathVariable Long tenantId,
                                                                      @PathVariable Long serviceId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (serviceId == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_ID_IS_NULL);
        }
        return Results.success(llmManageService.prepareRemoveLlmService(userId, tenantId, serviceId));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @PostMapping("/{tenantId}/remove/{serviceId}")
    public Result<Boolean> removeLlmService(@CurrentUser Long userId,
                                            @PathVariable Long tenantId,
                                            @PathVariable Long serviceId,
                                            @RequestBody @Valid LlmServiceRemoveReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (serviceId == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_ID_IS_NULL);
        }
        return Results.success(llmManageService.removeLlmService(userId, tenantId, serviceId, requestParam));
    }

    @GetMapping("/{tenantId}/get-llm-count")
    public Result<Integer> getLlmServiceCount(@CurrentUser Long userId,
                                              @PathVariable Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(llmManageService.getLlmServiceCount(userId, tenantId));
    }
}
