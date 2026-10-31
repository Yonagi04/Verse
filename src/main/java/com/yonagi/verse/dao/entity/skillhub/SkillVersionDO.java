package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 已批准的不可变内容版本及作者声明。 */
@Getter
@Setter
@TableName("t_skill_version")
public class SkillVersionDO {
    /** 不可变已批准版本标识。 */
    @TableId(type = IdType.INPUT)
    private Long versionId;
    /** 所属Skill。 */
    private Long skillId;
    /** 平台SemVer且不可覆盖。 */
    private String versionLabel;
    /** 不可变制品。 */
    private Long artifactId;
    /** 批准并完成发布的提交。 */
    private Long approvedSubmissionId;
    /** AVAILABLE或BLOCKED。 */
    private String status;
    /** 首版更新说明。 */
    private String releaseNotes;
    /** 冻结的许可来源依赖声明。 */
    private String declarationsJson;
    /** 批准发布时间。 */
    private LocalDateTime publishedAt;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
