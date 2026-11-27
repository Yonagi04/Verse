package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteFolderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 注销事务中的个人关系清理；调用方已持账号锁，不要求发布者存在。 */
@Service
@RequiredArgsConstructor
public class SkillFavoriteCleanupService {
    private final SkillFavoriteMapper favorites;
    private final SkillFavoriteFolderMapper folders;

    /** 删除本人两表，重复调用安全，不影响其他人收藏的源资源。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void clear(Long userId) {
        SkillFavoriteRules.positiveId(userId);
        favorites.deleteAllOwned(userId);
        folders.deleteAllOwned(userId);
    }
}
