package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

/**
 * LLM 调用审计详情响应 — 元数据 + 完整 prompt/response（回源 S3）。
 *
 * @author Yonagi
 */
@Data
public class LlmAuditDetailRespDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String requestId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 用户名（冗余）
     */
    private String username;

    private String model;

    private String promptPreview;

    private String responsePreview;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    /**
     * SUCCESS / FAIL
     */
    private String status;

    private String errorCode;

    private Date createTime;

    /**
     * 完整输入（OpenAI 兼容 JSON，objectKey 缺失时为 null）
     */
    private String prompt;

    /**
     * 完整输出（OpenAI 兼容 JSON，失败或 objectKey 缺失时为 null）
     */
    private String response;
}
