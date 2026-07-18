package com.yonagi.verse.dto.resp;

import lombok.Data;

import java.util.Date;

/**
 * 用户信息响应
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/05/18 19:41
 */
@Data
public class UserRespDTO {

    private Long userId;

    private String username;

    private String nickname;

    private String email;

    private String phone;
}
