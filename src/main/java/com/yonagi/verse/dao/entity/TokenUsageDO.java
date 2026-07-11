package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Token 消耗记录实体
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Data
@TableName("t_token_usage")
public class TokenUsageDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 用户ID（业务ID）
     */
    private Long userId;

    /**
     * 租户ID（业务ID）
     */
    private Long tenantId;

    /**
     * API Key ID（业务ID）
     */
    private Long apiKeyId;

    /**
     * LLM服务ID（业务ID）
     */
    private Long serviceId;

    /**
     * 实际调用的模型名
     */
    private String model;

    /**
     * 输入Token数
     */
    private Integer promptTokens;

    /**
     * 输出Token数
     */
    private Integer completionTokens;

    /**
     * 总Token数
     */
    private Integer totalTokens;

    /**
     * 请求追踪ID
     */
    private String requestId;

    /**
     * 创建时间
     */
    private Date createTime;
}
