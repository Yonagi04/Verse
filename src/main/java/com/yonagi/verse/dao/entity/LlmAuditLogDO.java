package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * LLM 调用审计索引实体 — 只存概略与 objectKey，完整内容在 S3。
 * 不继承 {@code BaseDO}，createTime 由消费者手动填充。
 *
 * @author Yonagi
 */
@Data
@TableName("t_llm_audit_log")
public class LlmAuditLogDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 请求追踪ID
     */
    private String requestId;

    /**
     * 租户ID（业务ID）
     */
    private Long tenantId;

    /**
     * 用户ID（业务ID）
     */
    private Long userId;

    /**
     * API Key ID（业务ID）
     */
    private Long apiKeyId;

    /**
     * LLM服务ID（业务ID）
     */
    private Long serviceId;

    /**
     * 实际调用模型别名
     */
    private String model;

    /**
     * 输入 prompt 概略
     */
    private String promptPreview;

    /**
     * 输出 response 概略
     */
    private String responsePreview;

    /**
     * 输入 prompt 在 S3 的 objectKey
     */
    private String promptObjectKey;

    /**
     * 输出 response 在 S3 的 objectKey
     */
    private String responseObjectKey;

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
     * 调用耗时（毫秒）
     */
    private Integer latencyMs;

    /**
     * SUCCESS / FAIL
     */
    private String status;

    /**
     * 失败时的错误码
     */
    private String errorCode;

    /**
     * 创建时间
     */
    private Date createTime;
}
