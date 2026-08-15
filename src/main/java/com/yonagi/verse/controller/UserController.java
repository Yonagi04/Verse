package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.AvatarService;
import com.yonagi.verse.service.LoginDeviceService;
import com.yonagi.verse.service.LoginHistoryService;
import com.yonagi.verse.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/05/18 19:44
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final LoginDeviceService loginDeviceService;
    private final LoginHistoryService loginHistoryService;

    @GetMapping("/hasUsername")
    public Result<Boolean> hasUsername(@RequestParam String username) {
        return Results.success(userService.hasUsername(username));
    }

    @PostMapping("/register")
    public Result<UserRegisterRespDTO> register(@Valid @RequestBody UserRegisterReqDTO requestParam) {
        UserRegisterRespDTO dto = userService.register(requestParam);
        return Results.success(dto);
    }

    @PostMapping("/login")
    public Result<UserLoginRespDTO> login(@Valid @RequestBody UserLoginReqDTO requestParam,
                                          HttpServletRequest request) {
        UserLoginRespDTO dto = userService.login(requestParam, request);
        return Results.success(dto);
    }

    @GetMapping("/me")
    public Result<UserRespDTO> getCurrentUser(@CurrentUser Long userId,
                                              @RequestParam(defaultValue = "true") boolean mask) {
        UserRespDTO dto = userService.getCurrentUser(userId, mask);
        return Results.success(dto);
    }

    @GetMapping("/getUserInfo")
    public Result<UserInfoRespDTO> getUserInfo(@RequestParam Long userId) {
        return Results.success(userService.getUserInfo(userId));
    }

    @PostMapping("/me")
    public Result<Boolean> updateProfile(@CurrentUser Long userId,
                                         @Valid @RequestBody UserUpdateReqDTO requestParam) {
        return Results.success(userService.updateProfile(userId, requestParam));
    }

    @GetMapping("/logout")
    public Result<Boolean> logout(@CurrentUser Long userId,
                                  HttpServletRequest request) {
        return Results.success(userService.logout(userId, request));
    }

    @PostMapping("/updatePassword")
    public Result<Boolean> updatePassword(@CurrentUser Long userId,
                                          @Valid @RequestBody UserUpdatePasswordReqDTO requestParam) {
        return Results.success(userService.updatePassword(userId, requestParam));
    }

    @PostMapping("/password/reset/sendCode")
    public Result<Boolean> sendingPhoneCode(@Valid @RequestBody UserSendingPhoneCodeReqDTO requestParam) {
        return Results.success(userService.sendingPhoneCode(requestParam));
    }

    @PostMapping("/password/reset/verifyCode")
    public Result<UserVerifyPhoneCodeRespDTO> verifyCode(@Valid @RequestBody UserVerifyPhoneCodeReqDTO requestParam) {
        return Results.success(userService.verifyCode(requestParam));
    }

    @PostMapping("/password/reset")
    public Result<Boolean> resetPassword(@Valid @RequestBody UserResetPasswordReqDTO requestParam) {
        return Results.success(userService.resetPassword(requestParam));
    }

    @GetMapping("/account/cancel/prepare")
    public Result<PrepareCloseAccountRespDTO> prepareCloseAccount(@CurrentUser Long userId) {
        return Results.success(userService.prepareCloseAccount(userId));
    }

    @PostMapping("/account/cancel/sendCode")
    public Result<Boolean> closeAccountSendCode(@CurrentUser Long userId) {
        return Results.success(userService.closeAccountSendCode(userId));
    }

    @PostMapping("/account/cancel/confirm")
    public Result<Boolean> confirmCloseAccount(@CurrentUser Long userId,
                                               @Valid @RequestBody ConfirmCloseAccountReqDTO requestParam) {
        return Results.success(userService.confirmCloseAccount(userId, requestParam));
    }

    @GetMapping("/me/devices")
    public Result<List<LoginDeviceRespDTO>> listDevices(@CurrentUser Long userId,
                                                         HttpServletRequest request) {
        return Results.success(loginDeviceService.listDevices(userId, request));
    }

    @DeleteMapping("/me/devices/{deviceId}")
    public Result<Boolean> kickDevice(@CurrentUser Long userId,
                                      @PathVariable String deviceId,
                                      HttpServletRequest request) {
        return Results.success(loginDeviceService.kickDevice(userId, deviceId, request));
    }

    @GetMapping("/me/login-history")
    public Result<LoginHistoryRespDTO> getLoginHistoryList(@CurrentUser Long userId,
                                                         @RequestParam @Valid Integer pageNum,
                                                         @RequestParam Integer pageSize) {
        return Results.success(loginHistoryService.getLoginHistoryList(userId, pageNum, pageSize));
    }

    @PostMapping("/me/avatar")
    public Result<String> uploadAvatar(@CurrentUser Long userId,
                                       @RequestBody MultipartFile file) {
        return Results.success(userService.uploadAvatar(userId, file));
    }

    @PostMapping("/me/privacy")
    public Result<Boolean> updatePrivacy(@CurrentUser Long userId,
                                         @RequestBody @Valid UserPrivacyUpdateReqDTO requestParam) {
        return Results.success(userService.updatePrivacy(userId, requestParam));
    }
}
