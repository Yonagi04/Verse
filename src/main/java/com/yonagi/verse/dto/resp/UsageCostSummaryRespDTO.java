package com.yonagi.verse.dto.resp;

import lombok.Data;

import java.util.Map;

@Data
public class UsageCostSummaryRespDTO {

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

    /** 汇总数据。 */
    private UsageCostTotalRespDTO total;

    /** 实际使用的筛选条件。 */
    private Map<String, Object> filter;
}
