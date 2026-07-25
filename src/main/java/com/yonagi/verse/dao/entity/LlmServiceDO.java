package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yonagi.verse.common.database.BaseDO;
import lombok.Data;

import java.util.Date;

/**
 * LLM 服务配置实体
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Data
@TableName("t_llm_service")
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
     * 默认模型名
     */
    private String modelName;

    /**
     * 状态：0=禁用, 1=启用
     */
    private Integer status;

    /**
     * 创建者用户ID
     */
    private Long createdBy;
}
