package com.yonagi.verse.dao.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;

@Data
@TableName("t_llm_cost_alert_delivery")
public class LlmCostAlertDeliveryDO {
    /** 任务主键。 */ @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO) private Long id;
    /** 所属事件。 */ private String eventId;
    /** 通知渠道或 ACTIVITY。 */ private String channel;
    /** 快照收件用户；系统或无收件人占位为 0，不作为用户身份。 */ private Long recipientUserId;
    /** 原联系方式查询摘要。 */ private String contactHash;
    /** 唯一外发地址摘要。 */ private String dispatchContactHash;
    /** 联系方式密文。 */ @ToString.Exclude private String contactEncrypted;
    /** 相同地址实际发送任务。 */ private Long canonicalDeliveryId;
    /** 固定消息请求标识。 */ private String requestId;
    /** 任务结果。 */ private String status;
    /** 脱敏原因。 */ private String reasonCode;
    /** 本地派发次数。 */ private Integer attemptCount;
    /** 下次认领时间。 */ private LocalDateTime nextRetryAt;
    /** 认领标识。 */ private String executionToken;
    /** 本地派发租约。 */ private LocalDateTime leaseUntil;
    /** 创建时间。 */ private LocalDateTime createTime;
    /** 更新时间。 */ private LocalDateTime updateTime;
}
