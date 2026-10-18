package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.*;

@Data
@TableName("t_personal_cost_alert_config")
public class UserCostAlertConfigDO {
    /** 本人业务ID。 */
    @TableId(type = IdType.INPUT)
    private Long userId;
    /** 日预警开关。 */
    private Boolean dailyEnabled;
    /** 日阈值整数分。 */
    private BigDecimal dailyThresholdFen;
    /** 周预警开关。 */
    private Boolean weeklyEnabled;
    /** 周阈值整数分。 */
    private BigDecimal weeklyThresholdFen;
    /** 共用渠道。 */
    private String channelsJson;
    /** 乐观版本。 */
    private Long configVersion;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 修改时间。 */
    private LocalDateTime updateTime;
}
