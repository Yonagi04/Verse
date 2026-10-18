package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.*;

@Data
@TableName("t_personal_usage_daily")
public class UserModelUsageDailyDO {
    /** 本人业务ID。 */
    private Long userId;
    /** 北京请求开始日。 */
    private LocalDate usageDate;
    /** 成功逻辑调用次数。 */
    private Long successCount;
    /** 已知费用原始精度分。 */
    private BigDecimal knownCostFen;
    /** 未知费用数。 */
    private Long unknownCostCount;
    /** 最近贡献时间。 */
    private LocalDateTime updatedAt;
}
