package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.util.List;

@Data
public class UsageCostBreakdownRespDTO {

    /** 分组维度。 */
    private String dimension;

    /** 当前页数据。 */
    private List<Item> items;

    /** 总记录数。 */
    private Long total;

    /** 总页数。 */
    private Long totalPages;

    /** 当前页码。 */
    private Integer page;

    /** 每页记录数。 */
    private Integer pageSize;

    @Data
    public static class Item extends UsageCostTotalRespDTO {

        /** 聚合结果内部维度 ID，不对外返回。 */
        @JsonIgnore
        private Long dimensionId;

        /** 服务 ID。 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long serviceId;

        /** 服务名称。 */
        private String serviceName;

        /** 模型提供方。 */
        private String provider;

        /** API Key ID。 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long apiKeyId;

        /** API Key 名称。 */
        private String apiKeyName;

        /** API Key 前缀。 */
        private String keyPrefix;
    }
}
