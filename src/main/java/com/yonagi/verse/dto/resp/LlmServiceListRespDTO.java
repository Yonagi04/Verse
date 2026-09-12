package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/22 18:29
 */
@Data
@Accessors(chain = true)
public class LlmServiceListRespDTO {

    /** 当前页服务列表。 */
    private List<LlmServiceInfo> serviceInfoList;

    /** 总记录数。 */
    private Long total;

    /** 总页数。 */
    private Long totalPages;

    /** 当前页码。 */
    private Integer page;

    /** 每页记录数。 */
    private Integer pageSize;

    @Data
    @Accessors(chain = true)
    public static class LlmServiceInfo {

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
         * 模型厂商侧记录的模型名称
         */
        private String modelName;

        /**
         * 提供商（如openai, anthropic）
         */
        private String provider;

        /**
         * 模型介绍
         */
        private String description;

        /**
         * 状态：0=禁用, 1=启用
         */
        private Integer status;

        /**
         * 创建者用户名
         */
        private String createdByUsername;

        /** 标签编码列表。 */
        private List<String> tagCodes;

        /** 上下文窗口大小。 */
        private Long contextWindow;

        /** 最大输出 Token 数。 */
        private Long maxOutputTokens;

        /** 计费状态：UNPRICED、TOKEN 或 REQUEST。 */
        private String billingStatus;

        /** 计费币种。 */
        private String currency;
    }
}
