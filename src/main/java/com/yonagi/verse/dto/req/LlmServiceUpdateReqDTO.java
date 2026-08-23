package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/23 09:52
 */
@Data
public class LlmServiceUpdateReqDTO {

    @NotBlank(message = "模型注册名称不能为空")
    @Length(max = 20, message = "模型注册名称不能超过20个字")
    private String name;

    @NotBlank(message = "供应商的API地址不能为空")
    private String apiUrl;

    @NotBlank(message = "供应商的API Key不能为空")
    private String apiKey;

    @NotBlank(message = "供应商提供的模型名称不能为空")
    private String modelName;
}
