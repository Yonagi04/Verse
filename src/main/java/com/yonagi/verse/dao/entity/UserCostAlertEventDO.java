package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.*;

@Data
@TableName("t_personal_cost_alert_event")
public class UserCostAlertEventDO {
    /** 稳定预警ID。 */
    @TableId(type = IdType.INPUT)
    private String eventId;
    /** 唯一收件人。 */
    private Long userId;
    /** DAY或WEEK。 */
    private String periodType;
    /** 周期开始，含。 */
    private LocalDateTime periodStart;
    /** 周期结束，不含。 */
    private LocalDateTime periodEnd;
    /** 预警事实时间。 */
    private LocalDateTime triggeredAt;
    /** 触发阈值快照。 */
    private BigDecimal thresholdFen;
    /** 触发费用快照。 */
    private BigDecimal knownCostFen;
    /** 触发费用质量快照。 */
    private Boolean costComplete;
    /** 触发配置版本。 */
    private Long configVersion;
    /** 触发渠道快照。 */
    private String channelsJson;
    /** 固定站内通知ID。 */
    private Long notificationId;
}
