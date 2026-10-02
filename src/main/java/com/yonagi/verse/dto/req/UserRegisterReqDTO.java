package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户注册请求
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/12 14:20
 */
@Data
public class UserRegisterReqDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度需在4~20字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名仅允许字母、数字、下划线")
    /** 唯一用户名 */
    private String username;

    @Size(max=50, message="昵称不能超过50个字符")
    /** 昵称 */
    private String nickname;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需在8~32字符之间")
    /** Verse登录密码 */
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Size(max=100, message="邮箱不能超过100个字符")
    @Email(message = "邮箱格式不正确")
    /** 联系邮箱 */
    private String email;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的手机号")
    /** 手机号 */
    private String phone;
}
