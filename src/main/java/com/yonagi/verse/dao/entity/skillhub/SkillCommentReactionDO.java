package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 每个账号对评论的当前反应，不保存点踩总数。 */
@Getter
@Setter
@TableName("t_skill_comment_reaction")
public class SkillCommentReactionDO {
    /** 反应关系标识。 */
    @TableId(type = IdType.INPUT)
    private Long id;
    /** 目标评论。 */
    private Long commentId;
    /** 执行反应的账号。 */
    private Long userId;
    /** LIKE或DISLIKE，取消时删除关系。 */
    private String reaction;
    /** 首次建立时间，UTC。 */
    private LocalDateTime createTime;
    /** 状态变化时间，UTC。 */
    private LocalDateTime updateTime;
}
