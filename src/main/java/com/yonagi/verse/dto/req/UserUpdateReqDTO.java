package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新用户信息请求
 *
 * @author Yonagi
 */
@Data
public class UserUpdateReqDTO {

    /** 昵称 */
    @Size(max=50, message="昵称不能超过50个字符")
    private String nickname;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max=100, message="邮箱不能超过100个字符")
    /** 联系邮箱 */
    private String email;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的手机号")
    /** 手机号 */
    private String phone;

    @Size(max = 255, message = "简介长度不能超过255个字符")
    /** 个人简介 */
    private String bio;

    /** 地区 */
    private String region;

    /** 时区 */
    private String timezone;
}
