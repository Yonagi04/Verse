package com.yonagi.verse.dao.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Data
@TableName("t_llm_cost_alert_event")
public class LlmCostAlertEventDO {
    /** 事件主键。 */ private Long id;
    /** 稳定业务标识。 */ private String eventId;
    /** 租户标识。 */ private Long tenantId;
    /** 模型标识。 */ private Long serviceId;
    /** 自然周期类型。 */ private String periodType;
    /** 周期开始。 */ private LocalDateTime periodStart;
    /** 周期结束。 */ private LocalDateTime periodEnd;
    /** WARNING 或 LIMIT。 */ private String eventType;
    /** 触发时间。 */ private LocalDateTime triggeredAt;
    /** 触发限额分。 */ private BigDecimal limitFen;
    /** 原始累计分。 */ private BigDecimal usedCostFen;
    /** 剩余分。 */ private BigDecimal remainingCostFen;
    /** 阈值快照。 */ private Integer thresholdPercent;
    /** 配置版本快照。 */ private Long configVersion;
    /** 渠道快照。 */ private String channelsJson;
    /** 租户名称快照。 */ private String tenantName;
    /** 服务名称快照。 */ private String serviceName;
    /** 最晚模型恢复时间。 */ private LocalDateTime retryAt;
    /** 触发时动态开关。 */ private Boolean activityEnabledSnapshot;
    /** 固定动态事件标识。 */ private String activityEventId;
    /** 固定公告标识。 */ private Long notificationId;
    /** 事件状态。 */ private String status;
    /** 创建时间。 */ private LocalDateTime createTime;
    /** 更新时间。 */ private LocalDateTime updateTime;
}
