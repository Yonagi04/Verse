package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String username;

    private String nickname;
}
