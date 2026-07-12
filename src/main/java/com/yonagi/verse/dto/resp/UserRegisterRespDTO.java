package com.yonagi.verse.dto.resp;

import lombok.Data;

/**
 * 用户注册响应
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/12 14:20
 */
@Data
public class UserRegisterRespDTO {

    private Long userId;

    private String username;
}
