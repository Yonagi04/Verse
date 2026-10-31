package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Skill聚合根及当前公开版本指针，不保存包正文的单表持久化访问。 */
public interface SkillMapper extends BaseMapper<SkillDO> {
    /**
     * @param skillId 已验证资源引用的Skill标识
     * @return 持行锁的聚合根，不存在时null；调用方先锁账号及发布者
     */
    @Select("""
            SELECT skill_id,publisher_id,slug,status,current_version_id,public_revision_id,
                   latest_release_at,revision,create_time,update_time
            FROM t_skill WHERE skill_id=#{skillId} FOR UPDATE
            """)
    SkillDO selectByIdForUpdate(@Param("skillId") Long skillId);
}
