package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 提交后冻结的发布快照及处理结果。 */
@Getter
@Setter
@TableName("t_skill_submission")
public class SkillSubmissionDO {
    /** 提交冻结快照标识。 */
    @TableId(type = IdType.INPUT)
    private Long submissionId;
    /** 所属Skill。 */
    private Long skillId;
    /** 来源草稿。 */
    private Long draftId;
    /** 可信提交账号。 */
    private Long submittedBy;
    /** 冻结展示修订。 */
    private Long displayRevisionId;
    /** 冻结制品。 */
    private Long artifactId;
    /** 冻结版本号。 */
    private String versionLabel;
    /** 发布版本标识。 */
    private Long versionId;
    /** 已完成发布为APPROVED。 */
    private String status;
    /** 审批绑定的请求快照摘要。 */
    private String snapshotHash;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
