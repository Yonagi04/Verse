package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

/** ZIP导入与检查任务的执行状态，不代表公开发布状态的单表持久化访问。 */
public interface SkillImportTaskMapper extends BaseMapper<SkillImportTaskDO> {
    /**
     * @param jobId 持统一账号/资源锁后的任务
     * @return 当前读任务并锁到外层事务结束
     */
    @Select("SELECT job_id,draft_id,owner_user_id,source_key,source_sha256,source_bytes,upload_token,upload_until,"
            + "processing_deadline,execution_token,lease_until,status,attempt_count,next_retry_at,error_code,"
            + "public_reason,artifact_id,"
            + "create_time,update_time FROM t_skill_job WHERE job_id=#{jobId} FOR UPDATE")
    SkillImportTaskDO selectByIdForUpdate(@Param("jobId") Long jobId);

    /**
     * @param now 当前UTC
     * @return 最多两个待领取或租约过期任务，最终资格由事务锁内重新确认
     */
    @Select("SELECT job_id FROM t_skill_job WHERE ((status IN ('QUEUED','RETRY_WAIT') AND next_retry_at<=#{now}) "
            + "OR (status='RUNNING' AND lease_until<=#{now})) ORDER BY next_retry_at,job_id LIMIT 2")
    List<Long> selectEligibleIds(@Param("now") LocalDateTime now);
}
