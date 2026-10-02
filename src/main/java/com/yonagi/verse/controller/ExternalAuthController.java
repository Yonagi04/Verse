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
@RequestMapping("/api/v1/auth/external")
public class ExternalAuthController {
    private final ExternalAuthService service;
    @GetMapping("/providers")
    public Result<List<ProviderInfo>> providers() { return Results.success(service.providers()); }
    @PostMapping("/flows")
    public Result<Start> start(@Valid @RequestBody ExternalFlowStartReqDTO input,HttpServletRequest request,HttpServletResponse response) {
        return Results.success(service.start(input,null,request,response));
    }
    @GetMapping("/callback/{provider}")
    public void callback(@PathVariable String provider,HttpServletRequest request,HttpServletResponse response) {
        response.setStatus(303); response.setHeader("Location",service.callback(provider,request,response));
    }
    @GetMapping("/flows/{id}")
    public Result<Context> context(@PathVariable String id,HttpServletRequest request) { return Results.success(service.context(id,request)); }
    @PostMapping("/flows/{id}/complete")
    public Result<LoginCompletion> complete(@PathVariable String id,@Valid @RequestBody ExternalCompleteReqDTO input,HttpServletRequest request) {
        return Results.success(service.complete(id,input,request));
    }
    @PostMapping("/flows/{id}/register")
    public Result<LoginCompletion> register(@PathVariable String id,@Valid @RequestBody UserRegisterReqDTO input,HttpServletRequest request) {
        return Results.success(service.register(id,input,request));
    }
    @PostMapping("/flows/{id}/continue-binding")
    public Result<Context> continueBinding(@PathVariable String id,HttpServletRequest request) { return Results.success(service.continueBinding(id,request)); }
    @PostMapping("/flows/{id}/attach-current-user")
    public Result<Context> attach(@PathVariable String id,@CurrentUser Long user,@Valid @RequestBody ExternalAttachReqDTO input,HttpServletRequest request) {
        return Results.success(service.attach(id,user,input,request));
    }
    @PostMapping("/flows/{id}/cancel")
    public Result<Void> cancel(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) {
        service.cancel(id,request,response,false); return Results.success();
    }
    @PostMapping("/flows/{id}/acknowledge")
    public Result<Void> acknowledge(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) {
        service.cancel(id,request,response,true); return Results.success();
    }
}
