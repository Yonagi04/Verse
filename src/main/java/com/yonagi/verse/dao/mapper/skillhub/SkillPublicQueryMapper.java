package com.yonagi.verse.dao.mapper.skillhub;

import com.yonagi.verse.dao.projection.SkillPublicProjection;
import com.yonagi.verse.dao.projection.SkillRevisionTagProjection;
import com.yonagi.verse.dao.projection.SkillOptionProjection;
import com.yonagi.verse.dao.query.SkillSearchCondition;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 公开内容定列查询；可见性条件在分页及count之前统一执行。 */
public interface SkillPublicQueryMapper {
    /**
     * @param query 已验证且完成LIKE转义的查询条件
     * @return 满足全部公开可用性和筛选条件的总数
     */
    long countAvailable(@Param("query") SkillSearchCondition query);

    /**
     * @param query 已验证且完成LIKE转义的有界分页条件
     * @return 查询条件确定的一页公开内容，最多24条
     */
    List<SkillPublicProjection> selectAvailablePage(@Param("query") SkillSearchCondition query);

    /**
     * @param skillId 公开资源稳定标识
     * @return 同时满足账号、作者、Skill、版本、制品条件的当前快照，否则null
     */
    SkillPublicProjection selectAvailableDetail(@Param("skillId") Long skillId);

    /**
     * @param skillId 公开资源稳定标识
     * @return 曾公开内容的有限占位，无正文/制品字段；未公开时null
     */
    SkillPublicProjection selectPublishedPlaceholder(@Param("skillId") Long skillId);

    /**
     * @param handle 平台公开作者标识
     * @param slug 作者下的稳定Skill标识
     * @return 曾公开的稳定Skill标识；草稿或不存在时null
     */
    Long selectPublishedId(@Param("handle") String handle, @Param("slug") String slug);

    /**
     * @param revisionIds 不超过24个展示修订标识
     * @return 对应标签关系，不逐卡查询
     */
    List<SkillRevisionTagProjection> selectRevisionTags(@Param("revisionIds") List<Long> revisionIds);

    /**
     * @return 平台可用于新发布的分类词表
     */
    List<SkillOptionProjection> selectCategories();

    /**
     * @return 平台可用于新发布的标签词表
     */
    List<SkillOptionProjection> selectTags();
}
