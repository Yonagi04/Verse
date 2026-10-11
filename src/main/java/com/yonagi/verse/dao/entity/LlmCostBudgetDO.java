package com.yonagi.verse.dao.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Data
@TableName("t_llm_cost_budget")
public class LlmCostBudgetDO {
    /** 配置主键。 */ private Long id;
    /** 租户标识。 */ private Long tenantId;
    /** 模型服务标识。 */ private Long serviceId;
    /** 限额开关。 */ private Boolean costLimitEnabled;
    /** 日限额分。 */ private BigDecimal dailyLimitFen;
    /** 周限额分。 */ private BigDecimal weeklyLimitFen;
    /** 月限额分。 */ private BigDecimal monthlyLimitFen;
    /** 预警草稿开关。 */ private Boolean warningEnabled;
    /** 所有周期共用阈值。 */ private Integer warningThresholdPercent;
    /** 预警渠道 JSON。 */ private String warningChannels;
    /** 当前配置并发版本；变更递增，不表示历史版本行。 */ private Long configVersion;
    /** 持久化费用故障原因；空值表示没有已知故障。 */ private String costDataReason;
    /** 创建时间。 */ private LocalDateTime createTime;
    /** 最近配置保存时间；费用累计不更新此字段。 */ private LocalDateTime updateTime;
}
