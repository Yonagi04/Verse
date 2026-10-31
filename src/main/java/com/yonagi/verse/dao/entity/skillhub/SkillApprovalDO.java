package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 提交审批单据与决定事实，不定义审核流程。 */
@Getter
@Setter
@TableName("t_skill_approval")
public class SkillApprovalDO {
    /** 审批单据标识。 */
    @TableId(type = IdType.INPUT)
    private Long approvalId;
    /** 一对一冻结提交。 */
    private Long submissionId;
    /** 事务内PENDING后APPROVED。 */
    private String status;
    /** 当前LOCAL_AUTO决定来源。 */
    private String decisionSource;
    /** 自动决定策略版本。 */
    private String policyVersion;
    /** 当前SYSTEM，不冒充人工审核。 */
    private String actorType;
    /** 批准所对应的快照摘要。 */
    private String requestSnapshotHash;
    /** 系统自动通过原因。 */
    private String reasonCode;
    /** 审批决定时间。 */
    private LocalDateTime decidedAt;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
