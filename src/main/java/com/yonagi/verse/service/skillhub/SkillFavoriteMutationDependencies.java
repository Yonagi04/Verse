package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.cache.QueryDependency;
import com.yonagi.verse.common.cache.QueryMutationBinding;
import com.yonagi.verse.common.cache.QueryMutationResolver;
import com.yonagi.verse.common.cache.QueryScope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 只接受已登记的显式所有者参数，归属与写 SQL 的 user_id 绑定保持一致。 */
@Component
@QueryMutationBinding(tables = {SkillFavoriteQueryDependencies.FAVORITE_TABLE,
        SkillFavoriteQueryDependencies.FOLDER_TABLE}, statementIds = {
        SkillFavoriteMutationDependencies.INSERT_FAVORITE, SkillFavoriteMutationDependencies.MOVE_FAVORITE,
        SkillFavoriteMutationDependencies.MOVE_FOLDER, SkillFavoriteMutationDependencies.DELETE_FAVORITE,
        SkillFavoriteMutationDependencies.DELETE_USER_FAVORITES, SkillFavoriteMutationDependencies.INSERT_FOLDER,
        SkillFavoriteMutationDependencies.RENAME_FOLDER, SkillFavoriteMutationDependencies.DELETE_FOLDER,
        SkillFavoriteMutationDependencies.DELETE_USER_FOLDERS})
public final class SkillFavoriteMutationDependencies implements QueryMutationResolver {
    private static final String FAVORITE_MAPPER = "com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteMapper.";
    private static final String FOLDER_MAPPER = "com.yonagi.verse.dao.mapper.skillhub.SkillFavoriteFolderMapper.";
    static final String INSERT_FAVORITE = FAVORITE_MAPPER + "insertOwned";
    static final String MOVE_FAVORITE = FAVORITE_MAPPER + "moveOwned";
    static final String MOVE_FOLDER = FAVORITE_MAPPER + "moveFolderToDefault";
    static final String DELETE_FAVORITE = FAVORITE_MAPPER + "deleteOwned";
    static final String DELETE_USER_FAVORITES = FAVORITE_MAPPER + "deleteAllOwned";
    static final String INSERT_FOLDER = FOLDER_MAPPER + "insertOwned";
    static final String RENAME_FOLDER = FOLDER_MAPPER + "renameOwned";
    static final String DELETE_FOLDER = FOLDER_MAPPER + "deleteOwned";
    static final String DELETE_USER_FOLDERS = FOLDER_MAPPER + "deleteAllOwned";
    private static final Map<String, String> STATEMENT_TABLES = Map.ofEntries(
            Map.entry(INSERT_FAVORITE, SkillFavoriteQueryDependencies.FAVORITE_TABLE),
            Map.entry(MOVE_FAVORITE, SkillFavoriteQueryDependencies.FAVORITE_TABLE),
            Map.entry(MOVE_FOLDER, SkillFavoriteQueryDependencies.FAVORITE_TABLE),
            Map.entry(DELETE_FAVORITE, SkillFavoriteQueryDependencies.FAVORITE_TABLE),
            Map.entry(DELETE_USER_FAVORITES, SkillFavoriteQueryDependencies.FAVORITE_TABLE),
            Map.entry(INSERT_FOLDER, SkillFavoriteQueryDependencies.FOLDER_TABLE),
            Map.entry(RENAME_FOLDER, SkillFavoriteQueryDependencies.FOLDER_TABLE),
            Map.entry(DELETE_FOLDER, SkillFavoriteQueryDependencies.FOLDER_TABLE),
            Map.entry(DELETE_USER_FOLDERS, SkillFavoriteQueryDependencies.FOLDER_TABLE));

    /** @return 当前目标表的账号范围；不使用实体归属或调用者线程上下文猜测所有者 */
    @Override
    public List<QueryDependency> resolve(String statementId, String table, Object parameter) {
        if (statementId == null || table == null || !table.equals(STATEMENT_TABLES.get(statementId))) {
            throw new IllegalArgumentException("收藏写语句与目标表不匹配");
        }
        if (!(parameter instanceof Map<?, ?> parameters) || !parameters.containsKey("userId")
                || !(parameters.get("userId") instanceof Long userId) || userId <= 0) {
            throw new IllegalArgumentException("收藏写入缺少显式账号归属");
        }
        return List.of(new QueryDependency(table, QueryScope.user(userId)));
    }
}
