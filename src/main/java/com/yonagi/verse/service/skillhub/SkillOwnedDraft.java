package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;

/** 已通过账号和所有权检查的本域对象，不穿透到HTTP层。 */
record SkillOwnedDraft(SkillPublisherDO publisher, SkillDO skill, SkillDraftDO draft) {
}
