package com.yonagi.verse.dto.req;

import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/05/18 19:42
 */
@Data
public class UserReqDTO {

    private String username;

    private String password;

    private String phone;

    private String mail;

}
