package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 个人收藏数据，独立物理删除，不继承租户基类。 */
@Getter
@Setter
@TableName("t_skill_favorite")
public class SkillFavoriteDO {
    /** 收藏关系标识，每次恢复重新生成。 */
    @TableId(type = IdType.INPUT)
    private Long favoriteId;
    /** 个人账号归属，与租户无关。 */
    private Long userId;
    /** 稳定 Skill 标识，不绑定版本。 */
    private Long skillId;
    /** 收藏夹标识，0 为内置默认夹。 */
    private Long folderId;
    /** 原始收藏时间，UTC；移动不改变。 */
    private LocalDateTime savedAt;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近整理时间，UTC。 */
    private LocalDateTime updateTime;
}
