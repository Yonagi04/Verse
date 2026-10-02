package com.yonagi.verse.dao.entity;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.*;
@Data
@TableName("t_external_auth_flow")
public class ExternalAuthFlowDO {
    /** 流程定位符 */
    @TableId(type = IdType.INPUT)
    private String flowId;
    /** 流程用途 */
    private String purpose;
    /** 流程阶段 */
    private String stage;
    /** 平台 */
    private String provider;
    /** 授权state散列 */
    private String stateHash;
    /** 标签页证明散列 */
    private String flowTokenHash;
    /** 浏览器证明散列 */
    private String browserCookieHash;
    /** 已验证身份ID */
    private Long externalIdentityId;
    /** 授权时关系版本 */
    private Long identityBindingVersion;
    /** 候选关系快照JSON */
    private String candidateSnapshot;
    /** 绑定目标用户 */
    private Long targetUserId;
    /** 绑定发起会话 */
    private String targetSessionId;
    /** 发起令牌指纹 */
    private String targetTokenHash;
    /** 用户安全版本 */
    private Long securityVersion;
    /** 近期验密时间 */
    private java.time.LocalDateTime recentVerifiedAt;
    /** 完成用户ID */
    private Long resultUserId;
    /** 完成关系ID */
    private Long resultBindingId;
    /** 注册是否已提交 */
    private Boolean registrationCompleted;
    /** 会话签发状态 */
    private String sessionStatus;
    /** 当前阶段到期时间 */
    private java.time.LocalDateTime expiresAt;
    /** 完成时间 */
    private java.time.LocalDateTime completedAt;
    /** 可公开错误原因 */
    private String errorReason;
    /** 创建时间 */
    private java.time.LocalDateTime createTime;
}
