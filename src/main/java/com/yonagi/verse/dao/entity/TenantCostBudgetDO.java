package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_tenant_cost_budget")
public class TenantCostBudgetDO {
    /** 配置主键。 */ private Long id;
    /** 租户标识，也是预算锁范围。 */ private Long tenantId;
    /** 限额开关。 */ private Boolean costLimitEnabled;
    /** 日限额分。 */ private BigDecimal dailyLimitFen;
    /** 周限额分。 */ private BigDecimal weeklyLimitFen;
    /** 月限额分。 */ private BigDecimal monthlyLimitFen;
    /** 保存的预警开关。 */ private Boolean warningEnabled;
    /** 三周期共用阈值。 */ private Integer warningThresholdPercent;
    /** 规范顺序的渠道 JSON。 */ private String warningChannels;
    /** 成本配置并发版本。 */ private Long configVersion;
    /** 完整性故障，空值表示无已知故障。 */ private String costDataReason;
    /** 创建时间。 */ private LocalDateTime createTime;
    /** 配置修改时间。 */ private LocalDateTime updateTime;
}
