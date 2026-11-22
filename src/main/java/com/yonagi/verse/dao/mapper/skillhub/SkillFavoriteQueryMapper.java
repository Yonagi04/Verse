package com.yonagi.verse.dao.mapper.skillhub;

import com.yonagi.verse.dao.projection.SkillFavoriteProjection;
import com.yonagi.verse.dao.projection.SkillFavoriteCountProjection;
import com.yonagi.verse.dao.query.SkillFavoriteCondition;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 从个人关系出发分页，缺失或失效源内容不影响收藏事实。 */
public interface SkillFavoriteQueryMapper {
    /** @return 本人各夹计数，空夹由用例补零。 */
    List<SkillFavoriteCountProjection> counts(@Param("userId") Long userId);
    /** @return 本人最多 24 个 Skill 的关系状态，不读取源内容。 */
    List<SkillFavoriteProjection> statuses(@Param("userId") Long userId, @Param("skillIds") List<Long> skillIds);
    /** @return 当前筛选总数；有关键词时仅匹配可用资源名称。 */
    long count(@Param("userId") Long userId, @Param("query") SkillFavoriteCondition query);
    /** @return 当前页最多 24 条，包含受控失效占位。 */
    List<SkillFavoriteProjection> page(@Param("userId") Long userId, @Param("query") SkillFavoriteCondition query);
    /** @return 新增收藏校验点的当前可用性，不加载正文和制品。 */
    boolean available(@Param("skillId") Long skillId);
}
