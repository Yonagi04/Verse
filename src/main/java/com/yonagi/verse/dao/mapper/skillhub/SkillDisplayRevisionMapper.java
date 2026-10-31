package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillDisplayRevisionDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 不可变的广场展示修订，由Skill公开指针选中的单表持久化访问。 */
public interface SkillDisplayRevisionMapper extends BaseMapper<SkillDisplayRevisionDO> {
    /**
     * @param revisionId 本事务新建的不可变展示修订
     * @param tagId 已通过平台词表校验的标签
     * @return 新增关系数量；唯一约束拒绝重复
     */
    @Insert("INSERT INTO t_skill_revision_tag(revision_id,tag_id) VALUES(#{revisionId},#{tagId})")
    int insertTag(@Param("revisionId") Long revisionId, @Param("tagId") String tagId);
}
