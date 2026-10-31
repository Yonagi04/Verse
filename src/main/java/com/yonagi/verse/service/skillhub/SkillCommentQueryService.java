package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillCommentDO;
import com.yonagi.verse.dao.entity.skillhub.SkillCommentReactionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillCommentMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillCommentReactionMapper;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.user.UserPublicProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 评论实时有界查询，页内用户/目标/反应批量装配，不缓存含本人状态的结果。 */
@Service
@RequiredArgsConstructor
public class SkillCommentQueryService {
    private static final int MAX_PAGE = 10000;
    private static final int MAX_SIZE = 50;
    private final SkillCommentAccessPolicy access;
    private final SkillCommentMapper comments;
    private final SkillCommentReactionMapper reactions;
    private final UserPublicProfileService profiles;

    /** @return 按最热或最新排序的可见父评论页。 */
    public SkillPageRespDTO<SkillCommentRespDTO> roots(Long actorId, Long skillId, String sort, int page, int size) {
        validate(page, size);
        if (!"hot".equals(sort) && !"new".equals(sort)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        access.read(actorId, skillId);
        long total = comments.countRoots(skillId);
        List<SkillCommentDO> rows = total == 0 ? List.of()
                : comments.roots(skillId, "hot".equals(sort), size, (page - 1) * size);
        return new SkillPageRespDTO<>(page, size, total, assemble(actorId, skillId, rows));
    }

    /** @return 当前Skill内同一根楼层的有效回复，正序独立分页。 */
    public SkillPageRespDTO<SkillCommentRespDTO> replies(Long actorId, Long skillId, Long rootId,
                                                        int page, int size) {
        validate(page, size);
        SkillCommentAccessPolicy.requireId(rootId);
        access.read(actorId, skillId);
        SkillCommentDO root = comments.selectById(rootId);
        if (root == null || !skillId.equals(root.getSkillId()) || root.getRootId() != 0
                || (root.getDelFlag() == 1 && root.getReplyCount() == 0)) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        long total = comments.countReplies(skillId, rootId);
        List<SkillCommentDO> rows = total == 0 ? List.of()
                : comments.replies(skillId, rootId, size, (page - 1) * size);
        return new SkillPageRespDTO<>(page, size, total, assemble(actorId, skillId, rows));
    }

    private List<SkillCommentRespDTO> assemble(Long actorId, Long skillId, List<SkillCommentDO> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> targetIds = rows.stream().map(SkillCommentDO::getReplyToCommentId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, SkillCommentDO> targets = targetIds.isEmpty() ? Map.of() : comments.targets(skillId, targetIds)
                .stream().collect(Collectors.toMap(SkillCommentDO::getCommentId, Function.identity(), (a, b) -> a));
        Set<Long> userIds = new HashSet<>();
        rows.stream().filter(row -> row.getDelFlag() == 0).forEach(row -> userIds.add(row.getAuthorUserId()));
        targets.values().stream().filter(row -> row.getDelFlag() == 0)
                .forEach(row -> userIds.add(row.getAuthorUserId()));
        Map<Long, UserPublicProfileRespDTO> users = profiles.read(userIds);
        List<Long> aliveIds = rows.stream().filter(row -> row.getDelFlag() == 0)
                .map(SkillCommentDO::getCommentId).toList();
        Map<Long, String> mine = aliveIds.isEmpty() ? Map.of() : reactions.selectList(
                Wrappers.lambdaQuery(SkillCommentReactionDO.class)
                        .in(SkillCommentReactionDO::getCommentId, aliveIds)
                        .eq(SkillCommentReactionDO::getUserId, actorId))
                .stream().collect(Collectors.toMap(SkillCommentReactionDO::getCommentId,
                        SkillCommentReactionDO::getReaction, (a, b) -> a));
        return rows.stream().map(row -> view(actorId, row, targets, users, mine)).toList();
    }

    private SkillCommentRespDTO view(Long actorId, SkillCommentDO row, Map<Long, SkillCommentDO> targets,
                                      Map<Long, UserPublicProfileRespDTO> users, Map<Long, String> mine) {
        boolean deleted = row.getDelFlag() == 1;
        SkillCommentReplyTargetRespDTO replyTo = null;
        if (row.getReplyToCommentId() != null) {
            SkillCommentDO target = targets.get(row.getReplyToCommentId());
            boolean targetDeleted = target == null || target.getDelFlag() == 1;
            replyTo = new SkillCommentReplyTargetRespDTO(row.getReplyToCommentId().toString(),
                    targetDeleted ? "已删除评论" : users.get(target.getAuthorUserId()).displayName(), targetDeleted);
        }
        return new SkillCommentRespDTO(row.getCommentId().toString(),
                (row.getRootId() == 0 ? row.getCommentId() : row.getRootId()).toString(),
                row.getReplyToCommentId() == null ? null : row.getReplyToCommentId().toString(),
                deleted ? null : users.get(row.getAuthorUserId()), deleted ? null : row.getContent(),
                row.getCreateTime().atOffset(ZoneOffset.UTC), deleted ? 0L : row.getLikeCount(),
                deleted ? "NONE" : mine.getOrDefault(row.getCommentId(), "NONE"), row.getReplyCount(),
                deleted, !deleted && actorId.equals(row.getAuthorUserId()), !deleted, replyTo);
    }

    private void validate(int page, int size) {
        if (page < 1 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
    }
}
