package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dto.req.UserLoginReqDTO;
import com.yonagi.verse.dto.req.UserRegisterReqDTO;
import com.yonagi.verse.dto.req.UserUpdateReqDTO;
import com.yonagi.verse.dto.resp.UserLoginRespDTO;
import com.yonagi.verse.dto.resp.UserRegisterRespDTO;
import com.yonagi.verse.dto.resp.UserRespDTO;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/05/18 19:39
 */
public interface UserService extends IService<UserDO> {

    Boolean hasUsername(String username);

    UserRegisterRespDTO register(UserRegisterReqDTO userRegisterReqDTO);

    UserLoginRespDTO login(UserLoginReqDTO reqDTO);

    UserRespDTO getCurrentUser(Long userId, boolean mask);

    Boolean updateProfile(Long userId, UserUpdateReqDTO reqDTO);
}
