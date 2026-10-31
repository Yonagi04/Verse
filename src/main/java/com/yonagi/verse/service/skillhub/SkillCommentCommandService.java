package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillCommentDO;
import com.yonagi.verse.dao.entity.skillhub.SkillCommentReactionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillCommentMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillCommentReactionMapper;
import com.yonagi.verse.dto.req.SkillCommentCreateReqDTO;
import com.yonagi.verse.dto.resp.SkillCommentCreatedRespDTO;
import com.yonagi.verse.dto.resp.SkillCommentReactionRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;

/** 评论写用例；账号锁串行幂等，根锁保护楼层计数，目标状态替代toggle。 */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
public class SkillCommentCommandService {
    private static final int MAX_CONTENT_CODE_POINTS = 1000;
    private static final String CREATE_COMMAND = "COMMENT_CREATE";
    private static final Set<String> REACTIONS = Set.of("NONE", "LIKE", "DISLIKE");
    private final SkillCommentAccessPolicy access;
    private final SkillCommentMapper comments;
    private final SkillCommentReactionMapper reactions;
    private final SkillIdempotencyService idempotency;
    private final SkillCommentNotificationService notifications;

    /** @return 首次创建201所需的回执，重试只返回ID不重新暴露已删除内容。 */
    public Creation create(Long actorId, Long skillId, SkillCommentCreateReqDTO input, String key) {
        String content = normalize(input == null ? null : input.content());
        Long replyTo = input.replyToCommentId();
        if (replyTo != null) {
            SkillCommentAccessPolicy.requireId(replyTo);
        }
        var lockedAccounts = access.lockAccounts(actorId, skillId);
        var request = Arrays.asList(skillId, replyTo, content);
        var replay = idempotency.replay(actorId, CREATE_COMMAND, key, request, SkillCommentCreatedRespDTO.class);
        if (replay != null) {
            return new Creation(replay, false);
        }
        var resource = access.lockAvailable(lockedAccounts);
        SkillCommentDO target = null;
        SkillCommentDO root = null;
        if (replyTo != null) {
            var locked = access.lockComment(skillId, replyTo);
            target = locked.target();
            root = locked.root();
            requireAlive(target);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SkillCommentDO row = new SkillCommentDO();
        row.setCommentId(SnowflakeIdUtil.nextId());
        row.setSkillId(skillId);
        row.setAuthorUserId(actorId);
        row.setRootId(root == null ? 0L : root.getCommentId());
        row.setReplyToCommentId(replyTo);
        row.setContent(content);
        row.setLikeCount(0L);
        row.setReplyCount(0L);
        row.setDelFlag(0);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        requireWritten(comments.insert(row));
        if (root != null) {
            updateReplyCount(root, 1, now);
        }
        notifications.notifyCreated(actorId, resource, target, content);
        SkillCommentCreatedRespDTO receipt = new SkillCommentCreatedRespDTO(row.getCommentId().toString(),
                (root == null ? row.getCommentId() : root.getCommentId()).toString(), skillId.toString());
        idempotency.record(actorId, CREATE_COMMAND, key, request, row.getCommentId(), receipt);
        return new Creation(receipt, true);
    }

    /** @return 本人最终反应及权威点赞数；重复PUT不会重复增减。 */
    public SkillCommentReactionRespDTO react(Long actorId, Long skillId, Long commentId, String reaction) {
        if (reaction == null || !REACTIONS.contains(reaction)) {
            throw invalid();
        }
        access.lockAvailable(access.lockAccounts(actorId, skillId));
        SkillCommentDO target = access.lockComment(skillId, commentId).target();
        requireAlive(target);
        SkillCommentReactionDO previous = reactions.selectOne(Wrappers.lambdaQuery(SkillCommentReactionDO.class)
                .eq(SkillCommentReactionDO::getCommentId, commentId).eq(SkillCommentReactionDO::getUserId, actorId));
        String old = previous == null ? "NONE" : previous.getReaction();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!old.equals(reaction)) {
            if ("NONE".equals(reaction)) {
                requireWritten(reactions.deleteById(previous.getId()));
            } else if (previous == null) {
                SkillCommentReactionDO row = new SkillCommentReactionDO();
                row.setId(SnowflakeIdUtil.nextId());
                row.setCommentId(commentId);
                row.setUserId(actorId);
                row.setReaction(reaction);
                row.setCreateTime(now);
                row.setUpdateTime(now);
                requireWritten(reactions.insert(row));
            } else {
                previous.setReaction(reaction);
                previous.setUpdateTime(now);
                requireWritten(reactions.updateById(previous));
            }
            long count = target.getLikeCount() + ("LIKE".equals(reaction) ? 1 : 0) - ("LIKE".equals(old) ? 1 : 0);
            if (count < 0) {
                throw new ServerException("评论点赞计数不一致", SkillErrorCodeEnum.UNAVAILABLE);
            }
            requireWritten(comments.update(null, Wrappers.lambdaUpdate(SkillCommentDO.class)
                    .eq(SkillCommentDO::getCommentId, commentId).eq(SkillCommentDO::getSkillId, skillId)
                    .eq(SkillCommentDO::getDelFlag, 0).set(SkillCommentDO::getLikeCount, count)
                    .set(SkillCommentDO::getUpdateTime, now)));
            target.setLikeCount(count);
        }
        return new SkillCommentReactionRespDTO(commentId.toString(), reaction, target.getLikeCount());
    }

    /** 只允许本人删除，下架不影响清理本人内容；重复删除不重复扣回复数。 */
    public void delete(Long actorId, Long skillId, Long commentId) {
        access.lockActor(actorId);
        var locked = access.lockComment(skillId, commentId);
        SkillCommentDO target = locked.target();
        if (!actorId.equals(target.getAuthorUserId())) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        if (target.getDelFlag() == 1) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        requireWritten(comments.update(null, Wrappers.lambdaUpdate(SkillCommentDO.class)
                .eq(SkillCommentDO::getCommentId, commentId).eq(SkillCommentDO::getSkillId, skillId)
                .eq(SkillCommentDO::getAuthorUserId, actorId).eq(SkillCommentDO::getDelFlag, 0)
                .set(SkillCommentDO::getDelFlag, 1).set(SkillCommentDO::getUpdateTime, now)));
        if (target.getRootId() != 0) {
            updateReplyCount(locked.root(), -1, now);
        }
    }

    private void updateReplyCount(SkillCommentDO root, int delta, LocalDateTime now) {
        long count = root.getReplyCount() + delta;
        if (count < 0) {
            throw new ServerException("评论回复计数不一致", SkillErrorCodeEnum.UNAVAILABLE);
        }
        requireWritten(comments.update(null, Wrappers.lambdaUpdate(SkillCommentDO.class)
                .eq(SkillCommentDO::getCommentId, root.getCommentId()).eq(SkillCommentDO::getSkillId, root.getSkillId())
                .set(SkillCommentDO::getReplyCount, count).set(SkillCommentDO::getUpdateTime, now)));
    }

    private void requireAlive(SkillCommentDO row) {
        if (row.getDelFlag() != 0) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
    }

    private String normalize(String content) {
        if (content == null) {
            throw invalid();
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > MAX_CONTENT_CODE_POINTS
                || normalized.codePoints().anyMatch(point -> Character.isISOControl(point)
                        && point != '\n' && point != '\t')) {
            throw invalid();
        }
        return normalized;
    }

    private ClientException invalid() {
        return new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
    }

    private void requireWritten(int count) {
        if (count != 1) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
    }

    /** 首次创建标志仅用于HTTP状态选择，回执本身没有正文。 */
    public record Creation(SkillCommentCreatedRespDTO receipt, boolean created) { }
}
