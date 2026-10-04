package com.yonagi.verse.dto.resp;

import java.util.List;

/** 单 Key 实时预算；未知金额与真实零消费严格区分。 */
public record ApiKeyCostStatusRespDTO(
        /** Key 业务标识。 */ String apiKeyId,
        /** 正式成本配置。 */ CostLimitConfig costLimit,
        /** 币种。 */ String currency,
        /** 周期时区。 */ String timezone,
        /** 查询时间。 */ String asOf,
        /** 数据完整性。 */ String availability,
        /** 成本状态。 */ String budgetState,
        /** 预计自然恢复时间。 */ String retryAt,
        /** 固定日、周、月明细。 */ List<PeriodStatus> periods,
        /** 所有生效限制。 */ List<Hit> limits) {
    public record PeriodStatus(
            /** 周期类型。 */ String period,
            /** 周期起点。 */ String periodStart,
            /** 周期终点。 */ String periodEnd,
            /** 限额，分。 */ String limitFen,
            /** 已用，分。 */ String usedCostFen,
            /** 剩余，分。 */ String remainingCostFen,
            /** 是否达到所填限额。 */ Boolean exceeded,
            /** 是否生效限制。 */ boolean effective,
            /** 已计算数量。 */ String calculatedCount,
            /** 未计价数量。 */ String unpricedCount,
            /** 不可计算数量。 */ String uncalculableCount,
            /** 不计费数量。 */ String notChargeableCount) { }
    public record Hit(
            /** 作用域。 */ String scope,
            /** 周期。 */ String period,
            /** 周期结束。 */ String periodEnd) { }
}
