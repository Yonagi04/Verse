package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.UserLoginReqDTO;
import com.yonagi.verse.dto.req.UserRegisterReqDTO;
import com.yonagi.verse.dto.req.UserUpdateReqDTO;
import com.yonagi.verse.dto.resp.UserLoginRespDTO;
import com.yonagi.verse.dto.resp.UserRegisterRespDTO;
import com.yonagi.verse.dto.resp.UserRespDTO;
import com.yonagi.verse.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/05/18 19:44
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/api/v1/user/hasUsername")
    public Result<Boolean> hasUsername(@RequestParam String username) {
        return Results.success(userService.hasUsername(username));
    }

    @PostMapping("/api/v1/users/register")
    public Result<UserRegisterRespDTO> register(@Valid @RequestBody UserRegisterReqDTO userRegisterReqDTO) {
        UserRegisterRespDTO dto = userService.register(userRegisterReqDTO);
        return Results.success(dto);
    }

    @PostMapping("/api/v1/users/login")
    public Result<UserLoginRespDTO> login(@Valid @RequestBody UserLoginReqDTO reqDTO) {
        UserLoginRespDTO dto = userService.login(reqDTO);
        return Results.success(dto);
    }

    @GetMapping("/api/v1/users/me")
    public Result<UserRespDTO> getCurrentUser(@CurrentUser Long userId,
                                              @RequestParam(defaultValue = "true") boolean mask) {
        UserRespDTO dto = userService.getCurrentUser(userId, mask);
        return Results.success(dto);
    }

    @PutMapping("/api/v1/users/me")
    public Result<Boolean> updateProfile(@CurrentUser Long userId,
                                         @RequestBody UserUpdateReqDTO reqDTO) {
        return Results.success(userService.updateProfile(userId, reqDTO));
    }
}
