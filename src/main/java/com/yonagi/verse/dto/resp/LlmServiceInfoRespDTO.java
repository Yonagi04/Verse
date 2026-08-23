package com.yonagi.verse.dto.resp;

import lombok.Data;

import java.util.Date;

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

    /**
     * 模型服务提供商上记录的模型名称
     */
    private String modelName;

    /**
     * 状态：0=禁用, 1=启用
     */
    private Integer status;

    /**
     * 创建者用户名
     */
    private String createdByUsername;

    /**
     * 创建时间
     */
    private Date createTime;
}
