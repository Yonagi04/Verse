package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.*;

@Data
@TableName("t_personal_cost_alert_delivery")
public class UserCostAlertDeliveryDO {
    /** 渠道任务ID。 */
    @TableId(type = IdType.INPUT)
    private Long deliveryId;
    /** 所属业务预警。 */
    private String eventId;
    /** 本人业务ID。 */
    private Long recipientUserId;
    /** 渠道。 */
    private String channel;
    /** 固定消息请求ID。 */
    private String requestId;
    /** 投递状态。 */
    private String status;
    /** 入队尝试次数。 */
    private Integer attemptCount;
    /** 下次派发时间。 */
    private LocalDateTime nextRetryAt;
    /** 派发租约令牌。 */
    private String executionToken;
    /** 派发租约截止。 */
    private LocalDateTime leaseUntil;
    /** 入队时联系方式哈希。 */
    private String contactHash;
    /** 当前消息尝试许可提交时间。 */
    private LocalDateTime sendPermittedAt;
    /** 消息执行令牌。 */
    private String permittedSendToken;
    /** 消息尝试序号。 */
    private Integer permittedSendAttempt;
    /** 结果原因。 */
    private String reasonCode;
    /** 取消意图永久保留，已许可尝试可完成但未来尝试不得复活。 */
    private LocalDateTime cancelRequestedAt;
    /** 任务创建时间。 */
    private LocalDateTime createTime;
    /** 任务更新时间。 */
    private LocalDateTime updateTime;
}
