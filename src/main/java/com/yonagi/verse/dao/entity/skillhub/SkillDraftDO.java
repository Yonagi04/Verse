package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 本人可编辑草稿，不直接决定公开内容。 */
@Getter
@Setter
@TableName("t_skill_draft")
public class SkillDraftDO {
    /** 本人草稿标识。 */
    @TableId(type = IdType.INPUT)
    private Long draftId;
    /** 预留Skill聚合根。 */
    private Long skillId;
    /** 可信会话确定的所有者。 */
    private Long ownerUserId;
    /** 已检查READY制品。 */
    private Long artifactId;
    /** 当前选定导入任务。 */
    private Long latestJobId;
    /** 有界且已校验的发布表单快照。 */
    private String metadataJson;
    /** 首版平台SemVer。 */
    private String versionLabel;
    /** EDITING、READY、SUBMITTED、CANCELLED或EXPIRED。 */
    private String status;
    /** 防止并发编辑覆盖的修订号。 */
    private Long revision;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
