package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillCommentReactionDO;

/** 反应事实持久化，唯一键保证每用户每评论一条。 */
public interface SkillCommentReactionMapper extends BaseMapper<SkillCommentReactionDO> {
}
