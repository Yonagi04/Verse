package com.yonagi.verse.controller;

import com.yonagi.verse.service.user.costalert.UserCostAlertSettingsService;
import com.yonagi.verse.service.user.costalert.UserCostAlertQueryService;
import com.yonagi.verse.common.convention.result.*;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserCostAlertErrorCode;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.UserCostAlertSettingsReplaceReqDTO;
import com.yonagi.verse.dto.resp.UserCostAlertSettingsRespDTO;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 用户费用预警设置与周期进度入口。 */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/cost-alert")
public class UserCostAlertController {
    private final UserCostAlertQueryService query;
    private final UserCostAlertSettingsService settings;
    @GetMapping
    public Result<UserCostAlertSettingsRespDTO> getSettings(@CurrentUser Long userId,HttpServletRequest request,HttpServletResponse response) {
        validateQuery(request,response); return Results.success(query.getSettings(userId));
    }
    @PutMapping
    public Result<UserCostAlertSettingsRespDTO> replaceSettings(@CurrentUser Long userId,@Valid @RequestBody UserCostAlertSettingsReplaceReqDTO body,
            HttpServletRequest request,HttpServletResponse response) {
        validateQuery(request,response); return Results.success(settings.replace(userId,body));
    }
    private void validateQuery(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if (!request.getParameterMap().isEmpty()) throw new ClientException(UserCostAlertErrorCode.INVALID_SETTINGS);
    }
}
