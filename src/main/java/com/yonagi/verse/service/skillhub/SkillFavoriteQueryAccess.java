package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.cache.QueryAccessPolicy;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillFavoriteFolderDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteFolderMapper;
import com.yonagi.verse.dao.query.SkillFavoriteCondition;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 命中、回源和降级共用的实时账号及夹归属检查。 */
@Component
@RequiredArgsConstructor
public class SkillFavoriteQueryAccess implements QueryAccessPolicy {
    private final AccountAvailabilityService accounts;
    private final SkillFavoriteFolderMapper folders;

    @Override
    public void check(Object[] arguments) {
        Long userId = (Long) arguments[0];
        requireAccount(userId);
        if (arguments.length > 1 && arguments[1] instanceof SkillFavoriteCondition condition) {
            requireFolder(userId, condition.folderId());
        }
    }

    /** 实时授权不缓存，不解析租户。 */
    public void requireAccount(Long userId) {
        if (userId == null || !accounts.isAccountActive(userId)) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
    }

    /** 逻辑默认夹无需查表；他人和缺失夹统一拒绝。 */
    public void requireFolder(Long userId, Long folderId) {
        SkillFavoriteRules.folderId(folderId, true);
        if (folderId != null && folderId != SkillFavoriteRules.DEFAULT_FOLDER
                && folders.selectOne(Wrappers.lambdaQuery(SkillFavoriteFolderDO.class)
                .eq(SkillFavoriteFolderDO::getUserId, userId)
                .eq(SkillFavoriteFolderDO::getFolderId, folderId)) == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
    }
}
