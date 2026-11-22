package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillFavoriteDO;
import com.yonagi.verse.dao.entity.skillhub.SkillFavoriteFolderDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteFolderMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteQueryMapper;
import com.yonagi.verse.dto.resp.SkillFavoriteFolderReceiptRespDTO;
import com.yonagi.verse.dto.resp.SkillFavoriteReceiptRespDTO;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 账号排他锁串行私人收藏写入；关系和回执在同一短事务提交。 */
@Service
@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
public class SkillFavoriteCommandService {
    private static final String FOLDER_CREATE = "FAV_FOLDER_CREATE";
    private static final String FOLDER_RENAME = "FAV_FOLDER_RENAME";
    private static final String FOLDER_DELETE = "FAV_FOLDER_DELETE";
    private static final String CREATE = "FAV_CREATE";
    private static final String MOVE = "FAV_MOVE";
    private static final String CANCEL = "FAV_CANCEL";
    private static final String RESTORE = "FAV_RESTORE";
    private static final int UNDO_SECONDS = 30;
    private final AccountAvailabilityService accounts;
    private final SkillFavoriteMapper favorites;
    private final SkillFavoriteFolderMapper folders;
    private final SkillFavoriteQueryMapper queries;
    private final SkillIdempotencyService idempotency;
    private final Clock clock;

    @Autowired
    public SkillFavoriteCommandService(AccountAvailabilityService accounts, SkillFavoriteMapper favorites,
            SkillFavoriteFolderMapper folders, SkillFavoriteQueryMapper queries, SkillIdempotencyService idempotency) {
        this(accounts, favorites, folders, queries, idempotency, Clock.systemUTC());
    }

    SkillFavoriteCommandService(AccountAvailabilityService accounts, SkillFavoriteMapper favorites,
            SkillFavoriteFolderMapper folders, SkillFavoriteQueryMapper queries, SkillIdempotencyService idempotency,
            Clock clock) {
        this.accounts = accounts;
        this.favorites = favorites;
        this.folders = folders;
        this.queries = queries;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** @return 新夹定位；同 UUID 回放不再次占用额度。 */
    public FolderCreation createFolder(Long userId, String input, String key) {
        String name = SkillFavoriteRules.name(input);
        lock(userId, key);
        SkillFavoriteFolderReceiptRespDTO replay = idempotency.replay(userId, FOLDER_CREATE, key, name,
                SkillFavoriteFolderReceiptRespDTO.class);
        if (replay != null) {
            return new FolderCreation(replay, false);
        }
        requireUniqueName(userId, name, null);
        if (folders.selectCount(Wrappers.lambdaQuery(SkillFavoriteFolderDO.class)
                .eq(SkillFavoriteFolderDO::getUserId, userId)) >= SkillFavoriteRules.FOLDER_LIMIT) {
            throw new ClientException("最多可创建 20 个自定义收藏夹，请先整理已有收藏夹", SkillErrorCodeEnum.LIMIT_EXCEEDED);
        }
        LocalDateTime now = now();
        SkillFavoriteFolderDO row = new SkillFavoriteFolderDO();
        row.setFolderId(SnowflakeIdUtil.nextId());
        row.setUserId(userId);
        row.setName(name);
        row.setNameKey(SkillFavoriteRules.nameKey(name));
        row.setCreateTime(now);
        row.setUpdateTime(now);
        requireWritten(folders.insert(row));
        SkillFavoriteFolderReceiptRespDTO receipt = new SkillFavoriteFolderReceiptRespDTO(
                row.getFolderId().toString(), true, 0);
        idempotency.record(userId, FOLDER_CREATE, key, name, row.getFolderId(), receipt);
        return new FolderCreation(receipt, true);
    }

    /** @return 改名回执，不改变任何收藏时间。 */
    public SkillFavoriteFolderReceiptRespDTO renameFolder(Long userId, Long folderId, String input, String key) {
        SkillFavoriteRules.positiveId(folderId);
        String name = SkillFavoriteRules.name(input);
        List<Object> payload = List.of(folderId, name);
        lock(userId, key);
        SkillFavoriteFolderReceiptRespDTO replay = idempotency.replay(userId, FOLDER_RENAME, key, payload,
                SkillFavoriteFolderReceiptRespDTO.class);
        if (replay != null) {
            return replay;
        }
        SkillFavoriteFolderDO folder = requireFolder(userId, folderId);
        requireUniqueName(userId, name, folderId);
        boolean changed = !folder.getName().equals(name);
        if (changed) {
            requireWritten(folders.update(null, Wrappers.lambdaUpdate(SkillFavoriteFolderDO.class)
                    .eq(SkillFavoriteFolderDO::getUserId, userId).eq(SkillFavoriteFolderDO::getFolderId, folderId)
                    .set(SkillFavoriteFolderDO::getName, name).set(SkillFavoriteFolderDO::getNameKey,
                            SkillFavoriteRules.nameKey(name)).set(SkillFavoriteFolderDO::getUpdateTime, now())));
        }
        SkillFavoriteFolderReceiptRespDTO receipt = new SkillFavoriteFolderReceiptRespDTO(folderId.toString(), changed, 0);
        idempotency.record(userId, FOLDER_RENAME, key, payload, folderId, receipt);
        return receipt;
    }

    /** @return 实际迁移数；夹和关系更新要么全部提交，要么全部回滚。 */
    public SkillFavoriteFolderReceiptRespDTO deleteFolder(Long userId, Long folderId, String key) {
        SkillFavoriteRules.positiveId(folderId);
        lock(userId, key);
        SkillFavoriteFolderReceiptRespDTO replay = idempotency.replay(userId, FOLDER_DELETE, key, folderId,
                SkillFavoriteFolderReceiptRespDTO.class);
        if (replay != null) {
            return replay;
        }
        SkillFavoriteFolderDO folder = findFolder(userId, folderId);
        int migrated = 0;
        if (folder != null) {
            migrated = favorites.update(null, Wrappers.lambdaUpdate(SkillFavoriteDO.class)
                    .eq(SkillFavoriteDO::getUserId, userId).eq(SkillFavoriteDO::getFolderId, folderId)
                    .set(SkillFavoriteDO::getFolderId, SkillFavoriteRules.DEFAULT_FOLDER)
                    .set(SkillFavoriteDO::getUpdateTime, now()));
            requireWritten(folders.delete(Wrappers.lambdaQuery(SkillFavoriteFolderDO.class)
                    .eq(SkillFavoriteFolderDO::getUserId, userId).eq(SkillFavoriteFolderDO::getFolderId, folderId)));
        }
        SkillFavoriteFolderReceiptRespDTO receipt = new SkillFavoriteFolderReceiptRespDTO(
                folderId.toString(), folder != null, migrated);
        idempotency.record(userId, FOLDER_DELETE, key, folderId, folderId, receipt);
        return receipt;
    }

    /** @return 已存在时保留原夹和原时间；首次新建才返回 created=true。 */
    public Creation create(Long userId, Long skillId, Long folderId, String key) {
        SkillFavoriteRules.positiveId(skillId);
        SkillFavoriteRules.folderId(folderId, false);
        List<Long> payload = List.of(skillId, folderId);
        lock(userId, key);
        SkillFavoriteReceiptRespDTO replay = idempotency.replay(userId, CREATE, key, payload,
                SkillFavoriteReceiptRespDTO.class);
        if (replay != null) {
            return new Creation(replay, false);
        }
        SkillFavoriteDO row = findSkill(userId, skillId);
        boolean created = row == null;
        if (created) {
            requireDestination(userId, folderId);
            requireCapacity(userId);
            if (!queries.available(skillId)) {
                throw new ClientException("此 Skill 当前不可用，无法新增收藏", SkillErrorCodeEnum.NOT_ACCESSIBLE);
            }
            row = insert(userId, skillId, folderId, now());
        }
        SkillFavoriteReceiptRespDTO receipt = receipt(created ? "CREATED" : "EXISTING", row, false);
        idempotency.record(userId, CREATE, key, payload, row.getFavoriteId(), receipt);
        return new Creation(receipt, created);
    }

    /** @return 只改变归属；失效源资源仍允许整理。 */
    public SkillFavoriteReceiptRespDTO move(Long userId, Long favoriteId, Long folderId, String key) {
        SkillFavoriteRules.positiveId(favoriteId);
        SkillFavoriteRules.folderId(folderId, false);
        List<Long> payload = List.of(favoriteId, folderId);
        lock(userId, key);
        SkillFavoriteReceiptRespDTO replay = idempotency.replay(userId, MOVE, key, payload,
                SkillFavoriteReceiptRespDTO.class);
        if (replay != null) {
            return replay;
        }
        SkillFavoriteDO row = findFavorite(userId, favoriteId);
        if (row == null) {
            throw new ClientException("此收藏已变化，请刷新后重试", SkillErrorCodeEnum.CONFLICT);
        }
        requireDestination(userId, folderId);
        if (!folderId.equals(row.getFolderId())) {
            requireWritten(favorites.update(null, Wrappers.lambdaUpdate(SkillFavoriteDO.class)
                    .eq(SkillFavoriteDO::getUserId, userId).eq(SkillFavoriteDO::getFavoriteId, favoriteId)
                    .set(SkillFavoriteDO::getFolderId, folderId).set(SkillFavoriteDO::getUpdateTime, now())));
            row.setFolderId(folderId);
        }
        SkillFavoriteReceiptRespDTO receipt = receipt("MOVED", row, false);
        idempotency.record(userId, MOVE, key, payload, favoriteId, receipt);
        return receipt;
    }

    /** @return 同事务保存可信恢复快照，缺失关系无撤销入口。 */
    public SkillFavoriteReceiptRespDTO cancel(Long userId, Long favoriteId, String key) {
        SkillFavoriteRules.positiveId(favoriteId);
        lock(userId, key);
        SkillFavoriteReceiptRespDTO replay = idempotency.replay(userId, CANCEL, key, favoriteId,
                SkillFavoriteReceiptRespDTO.class);
        if (replay != null) {
            return replay;
        }
        SkillFavoriteDO row = findFavorite(userId, favoriteId);
        SkillFavoriteReceiptRespDTO receipt;
        if (row == null) {
            receipt = new SkillFavoriteReceiptRespDTO("ABSENT", favoriteId.toString(), null, null, null,
                    false, null, false);
        } else {
            requireWritten(favorites.delete(Wrappers.lambdaQuery(SkillFavoriteDO.class)
                    .eq(SkillFavoriteDO::getUserId, userId).eq(SkillFavoriteDO::getFavoriteId, favoriteId)));
            receipt = new SkillFavoriteReceiptRespDTO("CANCELLED", favoriteId.toString(), row.getSkillId().toString(),
                    row.getFolderId().toString(), row.getSavedAt().atOffset(ZoneOffset.UTC), true,
                    now().plusSeconds(UNDO_SECONDS).atOffset(ZoneOffset.UTC), false);
        }
        idempotency.record(userId, CANCEL, key, favoriteId, favoriteId, receipt);
        return receipt;
    }

    /** @return 仅使用原取消 UUID 恢复；成功回放优先于期限检查，绝不覆盖新收藏。 */
    public SkillFavoriteReceiptRespDTO restore(Long userId, Long favoriteId, String key) {
        SkillFavoriteRules.positiveId(favoriteId);
        lock(userId, key);
        SkillFavoriteReceiptRespDTO replay = idempotency.replay(userId, RESTORE, key, favoriteId,
                SkillFavoriteReceiptRespDTO.class);
        if (replay != null) {
            return replay;
        }
        SkillFavoriteReceiptRespDTO previous = idempotency.replay(userId, CANCEL, key, favoriteId,
                SkillFavoriteReceiptRespDTO.class);
        if (previous == null || !Boolean.TRUE.equals(previous.removed()) || previous.undoExpiresAt() == null
                || now().atOffset(ZoneOffset.UTC).isAfter(previous.undoExpiresAt())) {
            throw new ClientException(SkillErrorCodeEnum.FAVORITE_UNDO_EXPIRED);
        }
        Long skillId = Long.valueOf(previous.skillId());
        SkillFavoriteDO row = findSkill(userId, skillId);
        boolean existing = row != null;
        boolean fallback = false;
        if (!existing) {
            requireCapacity(userId);
            Long folderId = Long.valueOf(previous.folderId());
            if (folderId != SkillFavoriteRules.DEFAULT_FOLDER && findFolder(userId, folderId) == null) {
                folderId = SkillFavoriteRules.DEFAULT_FOLDER;
                fallback = true;
            }
            row = insert(userId, skillId, folderId, previous.savedAt().withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime());
        }
        SkillFavoriteReceiptRespDTO receipt = receipt(existing ? "EXISTING" : "RESTORED", row, fallback);
        idempotency.record(userId, RESTORE, key, favoriteId, row.getFavoriteId(), receipt);
        return receipt;
    }

    private void lock(Long userId, String key) {
        SkillIdempotencyService.normalizeKey(key);
        if (userId == null || !accounts.lockActiveAccountExclusively(userId)) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
    }

    private void requireUniqueName(Long userId, String name, Long excluded) {
        SkillFavoriteFolderDO other = folders.selectOne(Wrappers.lambdaQuery(SkillFavoriteFolderDO.class)
                .eq(SkillFavoriteFolderDO::getUserId, userId)
                .eq(SkillFavoriteFolderDO::getNameKey, SkillFavoriteRules.nameKey(name)));
        if (other != null && !other.getFolderId().equals(excluded)) {
            throw new ClientException("已存在同名收藏夹，请换一个名称", SkillErrorCodeEnum.CONFLICT);
        }
    }

    private SkillFavoriteFolderDO requireFolder(Long userId, Long folderId) {
        SkillFavoriteFolderDO row = findFolder(userId, folderId);
        if (row == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return row;
    }

    private SkillFavoriteFolderDO findFolder(Long userId, Long folderId) {
        return folders.selectOne(Wrappers.lambdaQuery(SkillFavoriteFolderDO.class)
                .eq(SkillFavoriteFolderDO::getUserId, userId).eq(SkillFavoriteFolderDO::getFolderId, folderId));
    }

    private void requireDestination(Long userId, Long folderId) {
        if (folderId != SkillFavoriteRules.DEFAULT_FOLDER) {
            requireFolder(userId, folderId);
        }
    }

    private SkillFavoriteDO findSkill(Long userId, Long skillId) {
        return favorites.selectOne(Wrappers.lambdaQuery(SkillFavoriteDO.class)
                .eq(SkillFavoriteDO::getUserId, userId).eq(SkillFavoriteDO::getSkillId, skillId));
    }

    private SkillFavoriteDO findFavorite(Long userId, Long favoriteId) {
        return favorites.selectOne(Wrappers.lambdaQuery(SkillFavoriteDO.class)
                .eq(SkillFavoriteDO::getUserId, userId).eq(SkillFavoriteDO::getFavoriteId, favoriteId));
    }

    private void requireCapacity(Long userId) {
        if (favorites.selectCount(Wrappers.lambdaQuery(SkillFavoriteDO.class)
                .eq(SkillFavoriteDO::getUserId, userId)) >= SkillFavoriteRules.FAVORITE_LIMIT) {
            throw new ClientException("最多可收藏 1000 个 Skill，请先整理已有收藏", SkillErrorCodeEnum.LIMIT_EXCEEDED);
        }
    }

    private SkillFavoriteDO insert(Long userId, Long skillId, Long folderId, LocalDateTime savedAt) {
        SkillFavoriteDO row = new SkillFavoriteDO();
        row.setFavoriteId(SnowflakeIdUtil.nextId());
        row.setUserId(userId);
        row.setSkillId(skillId);
        row.setFolderId(folderId);
        row.setSavedAt(savedAt);
        row.setCreateTime(now());
        row.setUpdateTime(row.getCreateTime());
        requireWritten(favorites.insert(row));
        return row;
    }

    private SkillFavoriteReceiptRespDTO receipt(String outcome, SkillFavoriteDO row, boolean fallback) {
        return new SkillFavoriteReceiptRespDTO(outcome, row.getFavoriteId().toString(), row.getSkillId().toString(),
                row.getFolderId().toString(), row.getSavedAt().atOffset(ZoneOffset.UTC), false, null, fallback);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private void requireWritten(int count) {
        if (count != 1) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
    }

    /** 新建 HTTP 状态标志，不进入持久化回执。 */
    public record Creation(SkillFavoriteReceiptRespDTO receipt, boolean created) { }
    /** 新夹 HTTP 状态标志，不进入持久化回执。 */
    public record FolderCreation(SkillFavoriteFolderReceiptRespDTO receipt, boolean created) { }
}
