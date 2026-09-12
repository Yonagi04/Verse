package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yonagi.verse.common.database.BaseDO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * LLM 服务配置实体
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Data
@TableName("t_llm_service")
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LlmServiceDO extends BaseDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 服务唯一标识（业务ID）
     */
    private Long serviceId;

    /**
     * 租户ID（业务ID）
     */
    private Long tenantId;

    /**
     * 服务别名
     */
    private String name;

    /**
     * 提供商（如openai, anthropic）
     */
    private String provider;

    /**
     * API地址
     */
    private String apiUrl;

    /**
     * 真实的LLM API Key（AES加密存储）
     */
    private String apiKey;

    /**
     * 模型名(转发给模型厂商使用)
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
     * 创建者用户ID
     */
    private Long createdBy;

    /**
     * 模型级 RPM 上限（NULL=不限）
     */
    private Integer rateLimitRpm;

    /**
     * 模型级 TPM 上限（NULL=不限）
     */
    private Integer rateLimitTpm;

    /**
     * 备用模型 serviceId（单级降级，NULL=无降级）
     */
    private Long fallbackServiceId;

    /**
     * 上下文窗口大小
     */
    private Long contextWindow;

    /**
     * 最大输出 Token 数
     */
    private Long maxOutputTokens;

    /**
     * 当前生效定价版本 ID
     */
    private Long activePricingId;
}
