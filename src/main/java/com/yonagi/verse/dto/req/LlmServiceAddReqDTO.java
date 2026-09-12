package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/22 10:57
 */
@Data
public class LlmServiceAddReqDTO {

    /** 模型注册名称。 */
    @NotBlank(message = "模型注册名称不能为空")
    @Length(max = 20, message = "模型注册名称不能超过20个字")
    private String name;

    /** 模型提供方。 */
    @NotBlank(message = "供应商不能为空")
    private String provider;

    /** 模型提供方 API 地址。 */
    @NotBlank(message = "供应商的API地址不能为空")
    private String apiUrl;

    /** 模型提供方 API Key。 */
    @NotBlank(message = "供应商的API Key不能为空")
    private String apiKey;

    /** 模型提供方侧的模型名称。 */
    @NotBlank(message = "供应商提供的模型名称不能为空")
    private String modelName;

    /** 模型介绍，最多 100 个字符。 */
    @Length(max = 100, message = "模型介绍不能超过100个字符")
    private String description;

    /** 模型级每分钟请求数上限，零表示不限。 */
    @Min(value = 0, message = "RPM不能小于0")
    private Integer rpm;

    /** 模型级每分钟 Token 数上限，零表示不限。 */
    @Min(value = 0, message = "TPM不能小于0")
    private Integer tpm;

    /** 标签编码列表。 */
    private List<String> tagCodes;

    /** 上下文窗口大小。 */
    @Min(value = 1, message = "上下文窗口必须大于0")
    private Long contextWindow;

    /** 最大输出 Token 数。 */
    @Min(value = 1, message = "最大输出 Token 数必须大于0")
    private Long maxOutputTokens;

    /** 完整定价配置。 */
    private PricingConfigReqDTO pricing;
}
