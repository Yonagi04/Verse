package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.UserModelUsageDailyDO;
import org.apache.ibatis.annotations.*;

public interface UserModelUsageDailyMapper extends BaseMapper<UserModelUsageDailyDO> {
    @Insert("""
        INSERT INTO t_personal_usage_daily(user_id,usage_date,success_count,known_cost_fen,unknown_cost_count,updated_at)
        VALUES(#{userId},#{usageDate},#{successCount},#{knownCostFen},#{unknownCostCount},#{updatedAt})
        ON DUPLICATE KEY UPDATE success_count=success_count+VALUES(success_count),
          known_cost_fen=known_cost_fen+VALUES(known_cost_fen),unknown_cost_count=unknown_cost_count+VALUES(unknown_cost_count),updated_at=VALUES(updated_at)
        """)
    int contribute(UserModelUsageDailyDO contribution);
}
