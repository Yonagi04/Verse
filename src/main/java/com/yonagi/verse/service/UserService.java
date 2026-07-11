package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dto.resp.UserRespDTO;
import org.springframework.stereotype.Service;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/05/18 19:39
 */
public interface UserService extends IService<UserDO> {

    UserRespDTO getUserByUsername(String username);
}
