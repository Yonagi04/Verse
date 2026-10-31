package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillCommentDO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 评论有界查询与当前读，不在持久化层作权限决策。 */
public interface SkillCommentMapper extends BaseMapper<SkillCommentDO> {
    /** @return 包括软删除行的当前读，仅用于命令授权及幂等删除。 */
    SkillCommentDO lock(@Param("skillId") Long skillId, @Param("commentId") Long commentId);
    /** @return 可见父评论总数。 */
    long countRoots(@Param("skillId") Long skillId);
    /** @return 已在数据库过滤排序的父评论页。 */
    List<SkillCommentDO> roots(@Param("skillId") Long skillId, @Param("hot") boolean hot,
                              @Param("size") int size, @Param("offset") int offset);
    /** @return 根下未删除后代总数。 */
    long countReplies(@Param("skillId") Long skillId, @Param("rootId") Long rootId);
    /** @return 按发布时间正序的回复页。 */
    List<SkillCommentDO> replies(@Param("skillId") Long skillId, @Param("rootId") Long rootId,
                                @Param("size") int size, @Param("offset") int offset);
    /** @return 页内引用目标的身份和删除状态，不加载其正文。 */
    List<SkillCommentDO> targets(@Param("skillId") Long skillId, @Param("ids") List<Long> ids);
}
