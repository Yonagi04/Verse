package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import com.yonagi.verse.dto.req.CapabilityBindingReqDTO;

import java.util.Date;
import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/23 10:15
 */
@Data
public class LlmServiceInfoRespDTO {

    /**
     * 服务唯一标识（业务ID）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long serviceId;

    /**
     * 服务别名
     */
    private String name;

    /**
     * 提供商（如openai, anthropic）
     */
    private String provider;

    /**
     * 服务提供商的API地址
     */
    private String apiUrl;

    /**
     * 脱敏后的API Key
     */
    private String apiKey;

    /** 凭证模式。 */
    private String credentialMode;

    /** 无敏感信息的供应商配置。 */
    private java.util.Map<String, String> providerSettings;

    /** 服务的能力绑定。 */
    private List<CapabilityBindingReqDTO> capabilities;

    /**
     * 模型服务提供商上记录的模型名称
     */
    private String modelName;

    /**
     * 模型介绍
     */
    private String description;

    /**
     * 状态：0=禁用, 1=启用
     */
    private Integer status;

    /**
     * 模型级 RPM 上限（NULL=不限）
     */
    private Integer rateLimitRpm;

    /**
     * 模型级 TPM 上限（NULL=不限）
     */
    private Integer rateLimitTpm;

    /**
     * 备用模型 serviceId（NULL=无降级）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fallbackServiceId;

    /**
     * 创建者用户名
     */
    private String createdByUsername;

    /**
     * 创建时间
     */
    private Date createTime;

    /** 标签编码列表。 */
    private List<String> tagCodes;

    /** 上下文窗口大小。 */
    private Long contextWindow;

    /** 最大输出 Token 数。 */
    private Long maxOutputTokens;

    /** 当前定价配置。 */
    private PricingConfigRespDTO pricing;
}
