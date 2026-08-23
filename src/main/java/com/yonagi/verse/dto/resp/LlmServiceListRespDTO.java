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

    private List<LlmServiceInfo> serviceInfoList;

    private Long total;

    private Long totalPages;

    private Integer page;

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
         * 提供商（如openai, anthropic）
         */
        private String provider;

        /**
         * 状态：0=禁用, 1=启用
         */
        private Integer status;

        /**
         * 创建者用户名
         */
        private String createdByUsername;
    }
}
