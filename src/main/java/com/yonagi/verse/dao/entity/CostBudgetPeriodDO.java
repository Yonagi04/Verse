package com.yonagi.verse.dao.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;
/** 成本预算持久化记录。 */
@Data
@TableName("t_cost_budget_period")
public class CostBudgetPeriodDO {
    /** 累计记录主键。 */
    private Long id;
    /** 租户标识。 */
    private Long tenantId;
    /** 作用域标识。 */
    private Long scopeId;
    /** 作用域类型。 */
    private String scopeType;
    /** 周期类型。 */
    private String periodType;
    /** 周期开始。 */
    private LocalDateTime periodStart;
    /** 周期结束。 */
    private LocalDateTime periodEnd;
    /** 原精度累计，分。 */
    private BigDecimal usedCostFen;
    /** 已计算调用数量。 */
    private Long calculatedCount;
    /** 未计价调用数量。 */
    private Long unpricedCount;
    /** 不可计算调用数量。 */
    private Long uncalculableCount;
    /** 不计费调用数量。 */
    private Long notChargeableCount;
    /** 重建版本。 */
    private Long rebuildVersion;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}
