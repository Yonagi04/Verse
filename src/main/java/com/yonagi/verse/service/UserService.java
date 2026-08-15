package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/05/18 19:39
 */
public interface UserService extends IService<UserDO> {

    Boolean hasUsername(String username);

    UserRegisterRespDTO register(UserRegisterReqDTO requestParam);

    UserLoginRespDTO login(UserLoginReqDTO requestParam, HttpServletRequest request);

    UserRespDTO getCurrentUser(Long userId, boolean mask);

    Boolean updateProfile(Long userId, UserUpdateReqDTO requestParam);

    Boolean logout(Long userId, HttpServletRequest request);

    Boolean updatePassword(Long userId, UserUpdatePasswordReqDTO requestParam);

    Boolean sendingPhoneCode(UserSendingPhoneCodeReqDTO requestParam);

    UserVerifyPhoneCodeRespDTO verifyCode(UserVerifyPhoneCodeReqDTO requestParam);

    Boolean resetPassword(UserResetPasswordReqDTO requestParam);

    UserInfoRespDTO getUserInfo(Long userId);

    PrepareCloseAccountRespDTO prepareCloseAccount(Long userId);

    Boolean closeAccountSendCode(Long userId);

    Boolean confirmCloseAccount(Long userId, @Valid ConfirmCloseAccountReqDTO requestParam);

    String uploadAvatar(Long userId, MultipartFile file);

    Boolean updatePrivacy(Long userId, @Valid UserPrivacyUpdateReqDTO requestParam);

}
