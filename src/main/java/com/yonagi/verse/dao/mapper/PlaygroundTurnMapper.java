package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.PlaygroundTurnDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** PlayGround 轮次持久层。 */
@Mapper
public interface PlaygroundTurnMapper extends BaseMapper<PlaygroundTurnDO> {
    /** 只让仍在生成中的轮次进入一个终态。 */
    @Update("UPDATE t_playground_turn SET reply=#{reply},status=#{status},finished_at=NOW(3),update_time=NOW(3) " +
            "WHERE tenant_id=#{tenantId} AND owner_user_id=#{ownerId} AND session_id=#{sessionId} " +
            "AND turn_id=#{turnId} AND status IN ('PENDING','STREAMING')")
    int finish(@Param("tenantId") Long tenantId, @Param("ownerId") Long ownerId,
               @Param("sessionId") Long sessionId, @Param("turnId") Long turnId,
               @Param("reply") String reply, @Param("status") String status);

    /** 流中间歇保存片段，刷新后仍能看到已收到的内容。 */
    @Update("UPDATE t_playground_turn SET reply=#{reply},status='STREAMING',update_time=NOW(3) " +
            "WHERE tenant_id=#{tenantId} AND owner_user_id=#{ownerId} AND session_id=#{sessionId} " +
            "AND turn_id=#{turnId} AND status IN ('PENDING','STREAMING')")
    int savePartial(@Param("tenantId") Long tenantId, @Param("ownerId") Long ownerId,
                    @Param("sessionId") Long sessionId, @Param("turnId") Long turnId,
                    @Param("reply") String reply);
}
