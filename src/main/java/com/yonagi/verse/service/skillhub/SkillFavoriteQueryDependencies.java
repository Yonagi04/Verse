package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.cache.QueryDependency;
import com.yonagi.verse.common.cache.QueryDependencyResolver;
import com.yonagi.verse.common.cache.QueryScope;
import com.yonagi.verse.dao.query.SkillFavoriteCondition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 收藏关系属于账号；公开卡片依赖共享源，不能按收藏者归属失效。 */
@Component
public final class SkillFavoriteQueryDependencies implements QueryDependencyResolver {
    static final String FAVORITE_TABLE = "t_skill_favorite";
    static final String FOLDER_TABLE = "t_skill_favorite_folder";
    private static final List<String> PUBLIC_TABLES = List.of("t_skill", "t_skill_revision",
            "t_skill_revision_tag", "t_skill_version", "t_skill_artifact", "t_skill_publisher", "t_user");

    /** @return 覆盖当前查询全部源表的依赖；未知签名拒绝，防止误用私有范围 */
    @Override
    public List<QueryDependency> resolve(Object[] arguments) {
        if (arguments == null || arguments.length == 0
                || !(arguments[0] instanceof Long userId) || userId <= 0) {
            throw new IllegalArgumentException("收藏查询缺少有效账号归属");
        }
        boolean page = arguments.length == 2 && arguments[1] instanceof SkillFavoriteCondition;
        boolean statuses = arguments.length == 2 && arguments[1] instanceof List<?>;
        if (arguments.length != 1 && !page && !statuses) {
            throw new IllegalArgumentException("收藏查询参数形态未登记");
        }
        QueryScope owner = QueryScope.user(userId);
        List<QueryDependency> dependencies = new ArrayList<>();
        dependencies.add(new QueryDependency(FAVORITE_TABLE, owner));
        dependencies.add(new QueryDependency(FOLDER_TABLE, owner));
        if (page) {
            PUBLIC_TABLES.forEach(table -> dependencies.add(QueryDependency.table(table)));
        }
        return List.copyOf(dependencies);
    }
}
