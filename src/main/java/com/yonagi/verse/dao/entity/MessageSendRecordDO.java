package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.ToString;
import java.util.Date;

/** 短信和邮件共用的调用审计及短期任务；正文和验证码仅加密暂存，终态清空。 */
@Data
public abstract class MessageSendRecordDO {
    /** 数据库记录主键。 */
    @TableId(type = IdType.AUTO) private Long id;
    /** 调用方幂等请求标识。 */
    private String requestId;
    /** 带 pepper 的请求摘要，用于发现同 ID 不同内容。 */
    @ToString.Exclude private String requestFingerprint;
    /** 发信业务场景。 */
    private String scene;
    /** 可信业务用户，可为空。 */
    private Long userId;
    /** 可信业务租户，可为空。 */
    private Long tenantId;
    /** 实际选择的服务商。 */
    private String provider;
    /** 收件人 AES-GCM 密文。 */
    @ToString.Exclude private String recipientEncrypted;
    /** 收件人带 pepper 查询哈希。 */
    @ToString.Exclude private String recipientHash;
    /** QUEUED、SUBMITTING、ACCEPTED、REJECTED、UNKNOWN 或 EXPIRED。 */
    private String status;
    /** 服务商请求标识。 */
    private String providerRequestId;
    /** 服务商消息标识。 */
    private String providerMessageId;
    /** 脱敏协议错误码。 */
    private String errorCode;
    /** 任务持久化受理时间。 */
    private Date createTime;
    /** 本地记录最后更新时间。 */
    private Date updateTime;
    /** 调用耗时毫秒，不代表投递延迟。 */
    private Long durationMs;
    /** 待发送载荷 AES-GCM 密文，终态清空，不作为长期审计正文。 */
    @ToString.Exclude private String payloadEncrypted;
    /** 任务执行截止时间。 */
    private Date deadlineAt;
    /** 下次允许认领时间。 */
    private Date nextAttemptAt;
    /** 已认领执行的次数。 */
    private Integer attemptCount;
    /** 本次执行的 CAS 所有权标识。 */
    private String executionToken;
}
