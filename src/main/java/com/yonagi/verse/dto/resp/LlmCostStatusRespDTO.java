package com.yonagi.verse.dto.resp;

import java.util.List;

/** 管理员费用视图与成员摘要物理隔离，防止序列化泄漏。 */
public sealed interface LlmCostStatusRespDTO {
    record Warning(
            /** 保存开关。 */ boolean enabled,
            /** 当前是否生效。 */ boolean effective,
            /** 所有周期共用阈值。 */ int thresholdPercent,
            /** 阈值渠道。 */ List<String> channels) { }
    record Full(
            /** 模型标识。 */ String serviceId,
            /** FULL。 */ String visibility,
            /** 正式限额。 */ CostLimitConfig costLimit,
            /** 正式预警。 */ Warning costWarning,
            /** 价格资格。 */ boolean pricingEligible,
            /** 币种。 */ String currency,
            /** 固定时区。 */ String timezone,
            /** 累计来源。 */ List<String> consumptionSources,
            /** 查询时刻。 */ String asOf,
            /** 完整性。 */ String availability,
            /** 预算状态。 */ String budgetState,
            /** 最晚恢复时刻。 */ String retryAt,
            /** 三个周期。 */ List<ApiKeyCostStatusRespDTO.PeriodStatus> periods,
            /** 生效限制。 */ List<ApiKeyCostStatusRespDTO.Hit> limits) implements LlmCostStatusRespDTO { }
    record Summary(
            /** 模型标识。 */ String serviceId,
            /** SUMMARY。 */ String visibility,
            /** 预算状态。 */ String budgetState,
            /** 最晚恢复。 */ String retryAt,
            /** 固定时区。 */ String timezone,
            /** 查询时刻。 */ String asOf,
            /** 生效限制。 */ List<ApiKeyCostStatusRespDTO.Hit> limits,
            /** 脱敏说明。 */ String message) implements LlmCostStatusRespDTO { }
    record Batch(/** 当前页状态。 */ List<LlmCostStatusRespDTO> items) { }
}
