package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 本人可编辑草稿，不直接决定公开内容的单表持久化访问。 */
public interface SkillDraftMapper extends BaseMapper<SkillDraftDO> {
    /**
     * @param draftId 本人草稿标识
     * @return 持行锁的草稿，不存在时null；调用方先锁账号、发布者及Skill
     */
    @Select("""
            SELECT draft_id,skill_id,owner_user_id,artifact_id,latest_job_id,metadata_json,
                   version_label,status,revision,create_time,update_time
            FROM t_skill_draft WHERE draft_id=#{draftId} FOR UPDATE
            """)
    SkillDraftDO selectByIdForUpdate(@Param("draftId") Long draftId);
}
