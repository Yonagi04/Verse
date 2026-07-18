package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/18 21:15
 */
@Data
public class UserResetPasswordReqDTO {

    @NotBlank
    private String token;

    @NotBlank(message = "手机号不能为空")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需在8~32字符之间")
    private String password;
}
