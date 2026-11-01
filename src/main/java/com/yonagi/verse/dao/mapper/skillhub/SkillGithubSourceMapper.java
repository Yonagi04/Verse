package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillGithubSourceDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 来源快照在持有所属任务行锁时修改，不另建相反锁序。 */
public interface SkillGithubSourceMapper extends BaseMapper<SkillGithubSourceDO> {
    /** @return 至多100个已超期的私人用户等待任务 */
    @Select("SELECT job_id FROM t_skill_job WHERE source_type='GITHUB' AND "
            + "status IN ('WAITING_CONFIRMATION','WAITING_SELECTION') AND wait_until<=#{now} "
            + "ORDER BY wait_until,job_id LIMIT 100")
    List<Long> selectExpiredWaitIds(@Param("now") LocalDateTime now);

    /** @return 至多100个可清理源或元数据的终态任务，最终许可仍在任务锁内复核 */
    @Select("""
            SELECT g.job_id FROM t_skill_github_source g JOIN t_skill_job j ON j.job_id=g.job_id
            JOIN t_skill_draft d ON d.draft_id=j.draft_id
            WHERE j.status IN ('SUCCEEDED','FAILED','CANCELLED')
              AND ((j.update_time<=#{sourceExpiry} AND (g.tree_key IS NOT NULL OR j.source_key IS NOT NULL))
                OR j.update_time<=#{metadataExpiry} OR d.status IN ('CANCELLED','EXPIRED')
                OR EXISTS (SELECT c.user_id FROM t_skill_account_cleanup c
                           WHERE c.user_id=j.owner_user_id AND c.state='RUNNABLE'))
            ORDER BY j.job_id LIMIT 100
            """)
    List<Long> selectCleanupIds(@Param("sourceExpiry") LocalDateTime sourceExpiry,
                               @Param("metadataExpiry") LocalDateTime metadataExpiry);
}
