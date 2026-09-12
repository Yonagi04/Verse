package com.yonagi.verse.dto.resp;

import lombok.Data;
import java.util.List;

/** 用量维度排行响应。 */
@Data
public class UsageBreakdownRespDTO {
    /** 分组维度。 */ private String dimension;
    /** 排序指标。 */ private String orderBy;
    /** 未截断的全部分组汇总。 */ private UsageReportRespDTO.UsageMetrics total;
    /** 排行项目。 */ private List<Item> items;

    /** 单个排行项目。 */
    @Data
    public static class Item {
        /** 分组业务 ID。 */ private String id;
        /** 安全展示名称。 */ private String label;
        /** 排序指标占全部总量比例。 */ private String ratio;
        /** 分组指标。 */ private UsageReportRespDTO.UsageMetrics metrics;
    }
}
