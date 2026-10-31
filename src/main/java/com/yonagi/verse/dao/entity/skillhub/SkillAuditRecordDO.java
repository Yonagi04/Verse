package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 发布与下架等领域操作审计，不保存包正文或秘密。 */
@Getter
@Setter
@TableName("t_skill_audit")
public class SkillAuditRecordDO {
    /** 审计事实标识。 */
    @TableId(type = IdType.INPUT)
    private Long auditId;
    /** 发布或下架等领域操作。 */
    private String action;
    /** 可信操作账号。 */
    private Long actorId;
    /** Skill或提交标识。 */
    private Long targetId;
    /** 无秘密的结果及原因。 */
    private String detailsJson;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
