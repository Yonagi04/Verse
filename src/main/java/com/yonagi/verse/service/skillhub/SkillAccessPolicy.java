package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.enums.SkillPublisherStatusEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublisherMapper;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 全站个人资源归属；不接收租户管理员角色作为授权依据。 */
@Component
@RequiredArgsConstructor
public class SkillAccessPolicy {
    private final AccountAvailabilityService accountAvailabilityService;
    private final SkillPublisherMapper publisherMapper;
    private final SkillMapper skillMapper;
    private final SkillDraftMapper draftMapper;

    /**
     * @param userId 可信账号
     * @param draftId 本人草稿标识
     * @return 仅本人可读草稿及所属资源
     */
    public SkillOwnedDraft readDraft(Long userId, Long draftId) {
        if (!accountAvailabilityService.isAccountActive(userId)) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        SkillDraftDO draft = draftMapper.selectOne(Wrappers.lambdaQuery(SkillDraftDO.class)
                .eq(SkillDraftDO::getDraftId, draftId).eq(SkillDraftDO::getOwnerUserId, userId));
        if (draft == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        SkillDO skill = skillMapper.selectById(draft.getSkillId());
        SkillPublisherDO publisher = skill == null ? null : publisherMapper.selectById(skill.getPublisherId());
        verifyPublisher(userId, publisher);
        return new SkillOwnedDraft(publisher, skill, draft);
    }

    /**
     * @param userId 可信账号
     * @return 持行锁的本人发布者；调用方必须持有外层事务
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SkillPublisherDO lockPublisher(Long userId) {
        if (!accountAvailabilityService.lockActiveAccount(userId)) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        SkillPublisherDO publisher = publisherMapper.selectByUserIdForUpdate(userId);
        verifyPublisher(userId, publisher);
        return publisher;
    }

    /**
     * @param userId 可信账号
     * @param skillId 本人Skill标识
     * @return 已按账号→发布者→Skill锁定的资源
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SkillOwnedResource lockSkill(Long userId, Long skillId) {
        SkillPublisherDO publisher = lockPublisher(userId);
        SkillDO skill = skillMapper.selectByIdForUpdate(skillId);
        if (skill == null || !publisher.getPublisherId().equals(skill.getPublisherId())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return new SkillOwnedResource(publisher, skill);
    }

    /**
     * @param userId 可信账号
     * @param draftId 本人草稿标识
     * @return 按统一锁序重读并确认归属的草稿
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SkillOwnedDraft lockDraft(Long userId, Long draftId) {
        SkillPublisherDO publisher = lockPublisher(userId);
        SkillDraftDO reference = draftMapper.selectOne(Wrappers.lambdaQuery(SkillDraftDO.class)
                .eq(SkillDraftDO::getDraftId, draftId).eq(SkillDraftDO::getOwnerUserId, userId));
        if (reference == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        SkillDO skill = skillMapper.selectByIdForUpdate(reference.getSkillId());
        SkillDraftDO draft = draftMapper.selectByIdForUpdate(draftId);
        if (skill == null || draft == null || !publisher.getPublisherId().equals(skill.getPublisherId())
                || !userId.equals(draft.getOwnerUserId()) || !skill.getSkillId().equals(draft.getSkillId())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return new SkillOwnedDraft(publisher, skill, draft);
    }

    private void verifyPublisher(Long userId, SkillPublisherDO publisher) {
        if (publisher == null || !userId.equals(publisher.getUserId())
                || !SkillPublisherStatusEnum.ACTIVE.name().equals(publisher.getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
    }
}
