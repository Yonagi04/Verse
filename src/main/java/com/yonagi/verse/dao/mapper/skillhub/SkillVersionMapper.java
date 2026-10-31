package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 已批准的不可变内容版本及作者声明的单表持久化访问。 */
public interface SkillVersionMapper extends BaseMapper<SkillVersionDO> {
    /**
     * @param versionId 已持账号、发布者、Skill锁的版本
     * @return 当前读并持有版本锁
     */
    @Select("SELECT version_id,skill_id,version_label,artifact_id,approved_submission_id,status,release_notes,"
            + "declarations_json,published_at,create_time,update_time FROM t_skill_version "
            + "WHERE version_id=#{versionId} FOR UPDATE")
    SkillVersionDO selectByIdForUpdate(@Param("versionId") Long versionId);
}
