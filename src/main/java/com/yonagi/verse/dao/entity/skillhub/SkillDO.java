package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Skill聚合根及当前公开版本指针，不保存包正文。 */
@Getter
@Setter
@TableName("t_skill")
public class SkillDO {
    /** Skill稳定标识。 */
    @TableId(type = IdType.INPUT)
    private Long skillId;
    /** 个人发布者标识。 */
    private Long publisherId;
    /** 同作者下唯一且发布后固定的标识。 */
    private String slug;
    /** DRAFT、ACTIVE、OWNER_UNPUBLISHED或PLATFORM_DISABLED。 */
    private String status;
    /** 当前已批准版本指针。 */
    private Long currentVersionId;
    /** 当前已批准展示修订。 */
    private Long publicRevisionId;
    /** 最近内容版本发布时间。 */
    private LocalDateTime latestReleaseAt;
    /** 生命周期并发修订号。 */
    private Long revision;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
