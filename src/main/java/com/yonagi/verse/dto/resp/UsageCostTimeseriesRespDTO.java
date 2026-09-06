package com.yonagi.verse.dto.resp;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UsageCostTimeseriesRespDTO {

    /** 是否存在统计数据。 */
    private Boolean hasData;

    /** 计费币种。 */
    private String currency = "CNY";

    /** 金额单位。 */
    private String amountUnit = "FEN";

    /** 统计时区。 */
    private String timezone = "Asia/Shanghai";

    /** 尚未配置价格的模型数。 */
    private Long unpricedModelCount;

    /** 时间序列数据点。 */
    private List<Point> points;

    /** 实际使用的筛选条件。 */
    private Map<String, Object> filter;

    @Data
    public static class Point extends UsageCostTotalRespDTO {

        /** 时间桶开始时间。 */
        private String bucketStart;

        /** 时间桶结束时间。 */
        private String bucketEnd;
    }
}
