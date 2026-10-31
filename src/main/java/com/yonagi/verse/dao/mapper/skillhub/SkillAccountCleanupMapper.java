package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillAccountCleanupDO;

/** 注销后的本域处置进度，区分可执行任务与等待保留政策的单表持久化访问。 */
public interface SkillAccountCleanupMapper extends BaseMapper<SkillAccountCleanupDO> {
}
