package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;

/**
 * LLM 调用审计列表响应 — 索引 + 概略 + 分页信息。
 *
 * @author Yonagi
 */
@Data
@Accessors(chain = true)
public class LlmAuditListRespDTO {

    private List<LlmAuditInfo> auditList;

    private Long total;

    private Long totalPages;

    private Integer page;

    private Integer pageSize;

    @Data
    @Accessors(chain = true)
    public static class LlmAuditInfo {

        /**
         * 审计记录自增主键
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        /**
         * 请求追踪ID
         */
        private String requestId;

        /**
         * 用户ID（业务ID）
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long userId;

        /**
         * 用户名（冗余，供列表展示）
         */
        private String username;

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

        private Integer promptTokens;

        private Integer completionTokens;

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

        private Date createTime;
    }
}
