package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.PlaygroundWorkspaceDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PlaygroundWorkspaceMapper extends BaseMapper<PlaygroundWorkspaceDO> {
    /** 事务持有锁，保护轮次序号、预设版本和配置。 */
    @Select("SELECT * FROM t_playground_workspace WHERE tenant_id=#{tenant} AND owner_user_id=#{owner} " +
            "AND workspace_id=#{workspace} AND del_flag=0 FOR UPDATE")
    PlaygroundWorkspaceDO lock(@Param("tenant") Long tenant, @Param("owner") Long owner,
                               @Param("workspace") Long workspace);

    /** 只有全部尝试终态才释放组锁，适用于多实例。 */
    @Update("UPDATE t_playground_workspace SET generating=0,update_time=NOW(3) WHERE workspace_id=#{workspace} " +
            "AND NOT EXISTS (SELECT 1 FROM t_playground_attempt WHERE workspace_id=#{workspace} " +
            "AND status IN ('PENDING','STREAMING','STOPPING'))")
    int release(@Param("workspace") Long workspace);
}
