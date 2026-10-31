package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;

/** 已取得统一锁序并验证归属的Skill资源。 */
record SkillOwnedResource(SkillPublisherDO publisher, SkillDO skill) {
}
