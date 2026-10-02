package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.*;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.ExternalAuthRespDTO.*;
import com.yonagi.verse.service.external.ExternalAuthService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/external-accounts")
public class UserExternalAccountController {
    private final ExternalAuthService service;
    @GetMapping
    public Result<List<BindingInfo>> list(@CurrentUser Long user,HttpServletRequest request) { return Results.success(service.list(user,request)); }
    @PostMapping("/reauth")
    public Result<Reauth> reauth(@CurrentUser Long user,@Valid @RequestBody ExternalReauthReqDTO input,HttpServletRequest request) {
        return Results.success(service.reauth(user,input,request));
    }
    @PostMapping("/flows")
    public Result<Start> start(@CurrentUser Long user,@Valid @RequestBody ExternalFlowStartReqDTO input,HttpServletRequest request,HttpServletResponse response) {
        return Results.success(service.start(input,user,request,response));
    }
    @PostMapping("/flows/{id}/confirm")
    public Result<BindingResult> confirm(@CurrentUser Long user,@PathVariable String id,HttpServletRequest request) {
        return Results.success(service.confirm(id,user,request));
    }
    @PostMapping("/{bindingId}/unbind")
    public Result<BindingResult> unbind(@CurrentUser Long user,@PathVariable String bindingId,@Valid @RequestBody ExternalUnbindReqDTO input,HttpServletRequest request) {
        if (!bindingId.matches("[0-9]{1,19}")) throw new com.yonagi.verse.common.convention.exception.ClientException(com.yonagi.verse.common.enums.ExternalAuthErrorCodeEnum.ACTION_NOT_ALLOWED);
        return Results.success(service.unbind(user,bindingId,input,request));
    }
}
