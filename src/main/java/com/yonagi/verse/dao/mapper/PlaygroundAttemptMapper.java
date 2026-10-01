package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.PlaygroundAttemptDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlaygroundAttemptMapper extends BaseMapper<PlaygroundAttemptDO> {
    /** 与流认领竞争时，只有仍待调用的尝试才能直接停止。 */
    @Update("UPDATE t_playground_attempt SET status='STOPPED',finished_at=NOW(3),update_time=NOW(3) " +
            "WHERE tenant_id=#{tenant} AND owner_user_id=#{owner} AND attempt_id=#{attempt} AND status='PENDING'")
    int stopPending(@Param("tenant") Long tenant, @Param("owner") Long owner, @Param("attempt") Long attempt);
}
