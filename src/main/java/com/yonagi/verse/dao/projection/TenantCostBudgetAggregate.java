package com.yonagi.verse.dao.projection;

import lombok.Data;
import java.math.BigDecimal;

/** 周期账本的固定大小聚合；计数在业务层精确转换，避免溢出截断。 */
@Data
public class TenantCostBudgetAggregate {
    /** 已计算费用，保留原始分精度。 */
    private BigDecimal usedCostFen;
    /** 已计算事实数量。 */
    private BigDecimal calculatedCount;
    /** 未定价事实数量。 */
    private BigDecimal unpricedCount;
    /** 无法计算费用的事实数量。 */
    private BigDecimal uncalculableCount;
    /** 不计费事实数量。 */
    private BigDecimal notChargeableCount;
    /** 非法状态或已计算费用缺失的事实数量；不能静默视为零费用。 */
    private BigDecimal invalidCount;
}
