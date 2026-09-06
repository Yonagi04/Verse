package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UsageCostTotalRespDTO {

    /** 请求数。 */
    private Long requestCount = 0L;

    /** 输入 Token 数。 */
    private Long inputTokens = 0L;

    /** 缓存命中输入 Token 数。 */
    private Long cachedInputTokens = 0L;

    /** 输出 Token 数。 */
    private Long outputTokens = 0L;

    /** 总 Token 数。 */
    private Long totalTokens = 0L;

    /** 预估费用，单位为分。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal estimatedCostFen;

    /** 无法计算费用的请求数。 */
    private Long uncalculableRequestCount = 0L;
}
