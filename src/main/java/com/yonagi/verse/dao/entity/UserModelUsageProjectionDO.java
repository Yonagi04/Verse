package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.*;

@Data
@TableName("t_personal_usage_projection")
public class UserModelUsageProjectionDO {
    /** 本人业务ID。 */
    private Long userId;
    /** 原始事实ID。 */
    private Long usageId;
    /** 调用来源。 */
    private String source;
    /** 逻辑身份命名空间。 */
    private Long tenantId;
    /** 逻辑请求ID。 */
    private String requestId;
    /** 规范逻辑键，重复候选为空。 */
    private String logicalKey;
    /** 同用户同逻辑请求的首条用量事实。 */
    private Long canonicalUsageId;
    /** 工作项状态。 */
    private String status;
    /** 失败次数。 */
    private Integer attemptCount;
    /** 下次处理时间。 */
    private LocalDateTime nextRetryAt;
    /** 脱敏失败类型。 */
    private String lastErrorCode;
    /** 处理完成时间。 */
    private LocalDateTime projectedAt;
    /** 事实入队时间。 */
    private LocalDateTime createTime;
}
