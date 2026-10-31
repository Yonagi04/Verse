package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillAuditRecordDO;

/** 发布与下架等领域操作审计，不保存包正文或秘密的单表持久化访问。 */
public interface SkillAuditRecordMapper extends BaseMapper<SkillAuditRecordDO> {
}
