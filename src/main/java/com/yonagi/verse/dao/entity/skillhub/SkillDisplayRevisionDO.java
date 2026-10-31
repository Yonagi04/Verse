package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 不可变的广场展示修订，由Skill公开指针选中。 */
@Getter
@Setter
@TableName("t_skill_revision")
public class SkillDisplayRevisionDO {
    /** 不可变展示修订标识。 */
    @TableId(type = IdType.INPUT)
    private Long revisionId;
    /** 所属Skill。 */
    private Long skillId;
    /** 广场展示名称。 */
    private String name;
    /** 广场简短介绍。 */
    private String summary;
    /** 平台主分类标识。 */
    private String categoryId;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
