package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.cache.QueryCached;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.dao.entity.skillhub.SkillFavoriteFolderDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteFolderMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteQueryMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublicQueryMapper;
import com.yonagi.verse.dao.projection.SkillFavoriteProjection;
import com.yonagi.verse.dao.projection.SkillPublicProjection;
import com.yonagi.verse.dao.query.SkillFavoriteCondition;
import com.yonagi.verse.dto.resp.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static com.yonagi.verse.common.cache.QueryCacheTtl.*;

/** 个人只读入口；缓存依赖完整源表，源失效时不返回历史卡片字段。 */
@Service
@RequiredArgsConstructor
public class SkillFavoriteQueryService {
    private final SkillFavoriteFolderMapper folders;
    private final SkillFavoriteQueryMapper queries;
    private final SkillPublicQueryMapper publicQueries;
    private final SkillFavoriteQueryAccess access;

    /** @return 默认夹、自定义夹及不随搜索变化的权威计数。 */
    @QueryCached(keyPrefix = RedisKeyConstant.SKILL_FAVORITE_FOLDERS_KEY, seconds = MINUTES_30,
            access = SkillFavoriteQueryAccess.class, tables = {"t_skill_favorite_folder", "t_skill_favorite"})
    public SkillFavoriteFoldersRespDTO folders(Long userId) {
        access.requireAccount(userId);
        Map<Long, Long> counts = new HashMap<>();
        queries.counts(userId).forEach(row -> counts.put(row.getFolderId(), row.getCount()));
        List<SkillFavoriteFolderRespDTO> result = new ArrayList<>();
        result.add(new SkillFavoriteFolderRespDTO("0", SkillFavoriteRules.DEFAULT_NAME, true,
                counts.getOrDefault(0L, 0L)));
        folders.selectList(Wrappers.lambdaQuery(SkillFavoriteFolderDO.class)
                .eq(SkillFavoriteFolderDO::getUserId, userId).orderByAsc(SkillFavoriteFolderDO::getCreateTime)
                .orderByAsc(SkillFavoriteFolderDO::getFolderId)).forEach(row -> result.add(
                new SkillFavoriteFolderRespDTO(row.getFolderId().toString(), row.getName(), false,
                        counts.getOrDefault(row.getFolderId(), 0L))));
        return new SkillFavoriteFoldersRespDTO(List.copyOf(result), counts.values().stream().mapToLong(Long::longValue)
                .sum(), SkillFavoriteRules.FOLDER_LIMIT, SkillFavoriteRules.FAVORITE_LIMIT);
    }

    /** @return 按规范标识顺序返回状态，未收藏明确 favoriteId=null。 */
    @QueryCached(keyPrefix = RedisKeyConstant.SKILL_FAVORITE_STATUSES_KEY, seconds = MINUTES_10,
            access = SkillFavoriteQueryAccess.class, tables = {"t_skill_favorite_folder", "t_skill_favorite"})
    public List<SkillFavoriteStatusRespDTO> statuses(Long userId, List<Long> skillIds) {
        access.requireAccount(userId);
        List<Long> ids = SkillFavoriteRules.statusIds(skillIds);
        Map<Long, SkillFavoriteProjection> found = new HashMap<>();
        queries.statuses(userId, ids).forEach(row -> found.put(row.getSkillId(), row));
        return ids.stream().map(id -> {
            SkillFavoriteProjection row = found.get(id);
            return row == null ? new SkillFavoriteStatusRespDTO(id.toString(), null, null, null, null)
                    : new SkillFavoriteStatusRespDTO(id.toString(), row.getFavoriteId().toString(),
                    row.getFolderId().toString(), row.getFolderName(), utc(row.getSavedAt()));
        }).toList();
    }

    /** @return 按稳定规则分页；越界返回空页，由界面修正地址。 */
    @QueryCached(keyPrefix = RedisKeyConstant.SKILL_FAVORITES_KEY, seconds = MINUTES_10,
            access = SkillFavoriteQueryAccess.class, tables = {"t_skill_favorite_folder", "t_skill_favorite",
            "t_skill", "t_skill_revision", "t_skill_revision_tag", "t_skill_version", "t_skill_artifact",
            "t_skill_publisher", "t_user"})
    public SkillFavoritePageRespDTO page(Long userId, SkillFavoriteCondition query) {
        access.requireAccount(userId);
        access.requireFolder(userId, query.folderId());
        long total = queries.count(userId, query);
        if (total == 0) {
            return new SkillFavoritePageRespDTO(query.page(), query.size(), 0L, List.of());
        }
        List<SkillFavoriteProjection> rows = queries.page(userId, query);
        List<Long> revisionIds = rows.stream().filter(row -> Boolean.TRUE.equals(row.getAvailable()))
                .map(row -> row.getSkill().getRevisionId()).distinct().toList();
        Map<Long, List<String>> tags = new HashMap<>();
        if (!revisionIds.isEmpty()) {
            publicQueries.selectRevisionTags(revisionIds).forEach(tag -> tags.computeIfAbsent(tag.getRevisionId(),
                    ignored -> new ArrayList<>()).add(tag.getTagId()));
        }
        List<SkillFavoriteRespDTO> items = rows.stream().map(row -> new SkillFavoriteRespDTO(
                row.getFavoriteId().toString(), row.getSkillId().toString(), row.getFolderId().toString(),
                row.getFolderName(), utc(row.getSavedAt()), Boolean.TRUE.equals(row.getAvailable())
                ? "AVAILABLE" : "UNAVAILABLE", Boolean.TRUE.equals(row.getAvailable())
                ? card(row.getSkill(), tags) : null)).toList();
        return new SkillFavoritePageRespDTO(query.page(), query.size(), total, items);
    }

    private SkillCardRespDTO card(SkillPublicProjection row, Map<Long, List<String>> tags) {
        return new SkillCardRespDTO(row.getSkillId().toString(), new SkillPublisherRespDTO(
                row.getPublisherId().toString(), row.getHandle(), row.getPublicName()), row.getSlug(), row.getName(),
                row.getSummary(), row.getCategoryId(), List.copyOf(tags.getOrDefault(row.getRevisionId(), List.of())),
                row.getVersionLabel(), row.getVersionId().toString(), utc(row.getPublishedAt()), row.getSourceType());
    }

    private OffsetDateTime utc(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
