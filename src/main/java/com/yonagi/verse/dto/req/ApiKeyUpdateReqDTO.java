package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/22 09:40
 */
@Data
public class ApiKeyUpdateReqDTO {

    @NotBlank(message = "Api Key的备注名不能为空")
    @Size(max = 32, message = "Api Key的备注名长度不能超过32个字符")
    private String name;

    private Date expiresAt;
}
