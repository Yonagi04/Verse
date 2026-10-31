package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillCommentDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillCommentAccessMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillCommentMapper;
import com.yonagi.verse.dao.projection.SkillCommentResourceProjection;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 评论的账号、Skill可用性与目标所有权规则；共享锁与Skill生命周期排他锁互斥。 */
@Component
@RequiredArgsConstructor
public class SkillCommentAccessPolicy {
    private final AccountAvailabilityService accounts;
    private final SkillCommentAccessMapper resources;
    private final SkillCommentMapper comments;

    /** @return 仅有效账号可读的当前可用Skill，不加载正文也不缓存可用状态。 */
    public SkillCommentResourceProjection read(Long userId, Long skillId) {
        requireId(skillId);
        if (!accounts.isAccountActive(userId)) {
            throw denied();
        }
        SkillCommentResourceProjection resource = resources.available(skillId);
        if (resource == null) {
            throw denied();
        }
        return resource;
    }

    /**
     * 按账号ID升序取锁；作者失效只记录，允许调用方先回放已提交回执。
     * @return 原引用及当前账号有效性，不把预读当授权结果
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public LockedAccounts lockAccounts(Long userId, Long skillId) {
        requireId(skillId);
        SkillCommentResourceProjection reference = resources.reference(skillId);
        Long owner = reference == null ? null : reference.getOwnerUserId();
        boolean ownerActive;
        if (owner != null && owner < userId) {
            ownerActive = accounts.lockActiveAccount(owner);
            lockActor(userId);
        } else {
            lockActor(userId);
            ownerActive = owner != null && (owner.equals(userId) || accounts.lockActiveAccount(owner));
        }
        return new LockedAccounts(reference, ownerActive);
    }

    /** @param userId 有效操作者；排他账号锁同时串行该用户的发送幂等命令。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockActor(Long userId) {
        if (userId == null || !accounts.lockActiveAccountExclusively(userId)) {
            throw denied();
        }
    }

    /** @return 按Publisher→Skill→版本→制品当前读重新验证后的轻量资源。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SkillCommentResourceProjection lockAvailable(LockedAccounts locked) {
        SkillCommentResourceProjection ref = locked.reference();
        if (ref == null || !locked.ownerActive()) {
            throw denied();
        }
        var publisher = resources.lockPublisher(ref.getPublisherId());
        if (publisher == null || !ref.getOwnerUserId().equals(publisher.getUserId())
                || !"ACTIVE".equals(publisher.getStatus())) {
            throw denied();
        }
        var skill = resources.lockSkill(ref.getSkillId());
        if (skill == null || !ref.getPublisherId().equals(skill.getPublisherId())
                || !"ACTIVE".equals(skill.getStatus()) || skill.getCurrentVersionId() == null
                || skill.getPublicRevisionId() == null) {
            throw conflict();
        }
        var version = resources.lockVersion(skill.getCurrentVersionId());
        if (version == null || !skill.getSkillId().equals(version.getSkillId())
                || !"AVAILABLE".equals(version.getStatus())) {
            throw conflict();
        }
        var artifact = resources.lockArtifact(version.getArtifactId());
        if (artifact == null || !ref.getOwnerUserId().equals(artifact.getOwnerUserId())
                || !"READY".equals(artifact.getStatus())) {
            throw conflict();
        }
        // Skill共享锁保护版本/展示指针；此处重新取得名称，不使用锁前旧快照。
        SkillCommentResourceProjection current = resources.available(ref.getSkillId());
        if (current == null) {
            throw conflict();
        }
        return current;
    }

    /** @return 按根先于目标加锁的评论；包含删除行，供调用方决定拒绝或幂等回放。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public LockedComment lockComment(Long skillId, Long commentId) {
        requireId(skillId);
        requireId(commentId);
        SkillCommentDO reference = comments.selectById(commentId);
        if (reference == null || !skillId.equals(reference.getSkillId())) {
            throw denied();
        }
        Long rootId = reference.getRootId() == 0 ? commentId : reference.getRootId();
        SkillCommentDO root = comments.lock(skillId, rootId);
        SkillCommentDO target = rootId.equals(commentId) ? root : comments.lock(skillId, commentId);
        if (root == null || target == null || root.getRootId() != 0
                || (!rootId.equals(commentId) && !rootId.equals(target.getRootId()))) {
            throw denied();
        }
        return new LockedComment(root, target);
    }

    /** @param id 资源标识，不允许0或负数。 */
    public static void requireId(Long id) {
        if (id == null || id < 1) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
    }

    private ClientException denied() {
        return new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
    }

    private ClientException conflict() {
        return new ClientException(SkillErrorCodeEnum.CONFLICT);
    }

    /** 锁定的账号结果，不携带用户私有资料。 */
    public record LockedAccounts(SkillCommentResourceProjection reference, boolean ownerActive) { }

    /** 同楼根与实际目标的当前读。 */
    public record LockedComment(SkillCommentDO root, SkillCommentDO target) { }
}
