package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillIdempotencyRecordDO;

/** 关键命令的幂等回执，不替代长期业务唯一约束的单表持久化访问。 */
public interface SkillIdempotencyRecordMapper extends BaseMapper<SkillIdempotencyRecordDO> {
}
