package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 评论事实及事务内派生计数；生成可见列只用于SQL，不映射到写对象。 */
@Getter
@Setter
@TableName("t_skill_comment")
public class SkillCommentDO {
    /** 评论稳定标识。 */
    @TableId(type = IdType.INPUT)
    private Long commentId;
    /** 所属Skill，不绑定版本。 */
    private Long skillId;
    /** 发送者账号。 */
    private Long authorUserId;
    /** 父评论为0，回复为所属父评论标识。 */
    private Long rootId;
    /** 实际回复目标，父评论为空。 */
    private Long replyToCommentId;
    /** 纯文本正文，软删除后不再公开。 */
    private String content;
    /** 反应事实派生的点赞数。 */
    private Long likeCount;
    /** 父评论下未删除的全部后代数。 */
    private Long replyCount;
    /** 逻辑删除标志，0正常、1删除。 */
    private Integer delFlag;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
