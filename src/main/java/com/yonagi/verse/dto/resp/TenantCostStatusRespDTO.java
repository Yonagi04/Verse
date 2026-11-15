package com.yonagi.verse.dto.resp;

import java.util.List;

/** 成员摘要没有金额和通知配置字段。 */
public sealed interface TenantCostStatusRespDTO {
    record Full(
            /** 租户标识。 */ String tenantId,
            /** 可见级别。 */ String visibility,
            /** 同快照基础设置版本。 */ String observedSettingsVersion,
            /** 限额配置。 */ CostLimitConfig costLimit,
            /** 预警配置。 */ LlmCostStatusRespDTO.Warning costWarning,
            /** 币种。 */ String currency,
            /** 时区。 */ String timezone,
            /** 消耗来源。 */ List<String> consumptionSources,
            /** 查询时刻。 */ String asOf,
            /** 完整性。 */ String availability,
            /** 预算状态。 */ String budgetState,
            /** 最晚恢复时间。 */ String retryAt,
            /** 周期状态。 */ List<ApiKeyCostStatusRespDTO.PeriodStatus> periods,
            /** 当前命中。 */ List<ApiKeyCostStatusRespDTO.Hit> limits,
            /** 与准入分开的待核账摘要，未启用时为空。 */ Reconciliation reconciliation) implements TenantCostStatusRespDTO {
        public Full(String tenantId, String visibility, String observedSettingsVersion, CostLimitConfig costLimit,
                    LlmCostStatusRespDTO.Warning costWarning, String currency, String timezone, List<String> consumptionSources,
                    String asOf, String availability, String budgetState, String retryAt,
                    List<ApiKeyCostStatusRespDTO.PeriodStatus> periods, List<ApiKeyCostStatusRespDTO.Hit> limits) {
            this(tenantId, visibility, observedSettingsVersion, costLimit, costWarning, currency, timezone,
                    consumptionSources, asOf, availability, budgetState, retryAt, periods, limits, null);
        }
    }
    record Reconciliation(
            /** 当前周期仍待核对的案件数。 */ long pendingCount,
            /** 待确认的按次暂计金额，分。 */ String estimatedCostFen) {}
    record Summary(
            /** 租户标识。 */ String tenantId,
            /** 可见级别。 */ String visibility,
            /** 预算状态。 */ String budgetState,
            /** 最晚恢复时间。 */ String retryAt,
            /** 时区。 */ String timezone,
            /** 查询时刻。 */ String asOf,
            /** 脱敏命中。 */ List<ApiKeyCostStatusRespDTO.Hit> limits,
            /** 脱敏说明。 */ String message) implements TenantCostStatusRespDTO { }
}
