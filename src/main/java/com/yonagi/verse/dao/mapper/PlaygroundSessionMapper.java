package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.PlaygroundSessionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** PlayGround 会话原子状态更新。 */
@Mapper
public interface PlaygroundSessionMapper extends BaseMapper<PlaygroundSessionDO> {
    /** 只允许所有者在未生成时取得独占权。 */
    @Update("UPDATE t_playground_session SET generating=1,turn_count=turn_count+1,update_time=NOW(3) " +
            "WHERE tenant_id=#{tenantId} AND owner_user_id=#{ownerId} AND session_id=#{sessionId} " +
            "AND del_flag=0 AND generating=0")
    int claim(@Param("tenantId") Long tenantId, @Param("ownerId") Long ownerId,
              @Param("sessionId") Long sessionId);

    /** 仅由持有者释放生成权。 */
    @Update("UPDATE t_playground_session SET generating=0,update_time=NOW(3) " +
            "WHERE tenant_id=#{tenantId} AND owner_user_id=#{ownerId} AND session_id=#{sessionId} AND generating=1")
    int release(@Param("tenantId") Long tenantId, @Param("ownerId") Long ownerId,
                @Param("sessionId") Long sessionId);
}
