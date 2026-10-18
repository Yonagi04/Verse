package com.yonagi.verse.dao.mapper;

import org.apache.ibatis.annotations.Delete;

/** 用户模型调用读模型的分批清理。 */
public interface UserModelUsageCleanupMapper {
    @Delete("DELETE FROM t_personal_usage_projection WHERE user_id=#{userId} LIMIT 500") int work(Long userId);
    @Delete("DELETE FROM t_personal_usage_daily WHERE user_id=#{userId} LIMIT 500") int daily(Long userId);
}
