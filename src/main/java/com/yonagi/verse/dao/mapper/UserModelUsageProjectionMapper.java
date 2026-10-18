package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.*;
import org.apache.ibatis.annotations.*;
import java.time.*;
import java.util.List;

public interface UserModelUsageProjectionMapper extends BaseMapper<UserModelUsageProjectionDO> {
    @Select("""
        SELECT p.* FROM t_personal_usage_projection p
        JOIN t_user owner ON owner.user_id=p.user_id AND owner.status=1 AND owner.del_flag=0
        WHERE p.status='PENDING' AND p.next_retry_at<=#{now}
        ORDER BY p.next_retry_at,p.user_id,p.usage_id LIMIT 100
        """)
    List<UserModelUsageProjectionDO> due(LocalDateTime now);

    @Update("""
        UPDATE t_personal_usage_projection SET status=#{status},attempt_count=#{attemptCount},
          next_retry_at=#{nextRetryAt},last_error_code=#{lastErrorCode},projected_at=#{projectedAt}
        WHERE user_id=#{userId} AND usage_id=#{usageId}
        """)
    int finish(UserModelUsageProjectionDO row);

    /** 日期分组有界；缺工作项、失败或矛盾均不能被当成完整。 */
    @Select("""
        SELECT DISTINCT DATE(u.request_started_at) FROM t_token_usage u
        LEFT JOIN t_personal_usage_projection p ON p.user_id=u.user_id AND p.usage_id=u.id
        WHERE u.user_id=#{userId} AND u.request_started_at>=#{from} AND u.request_started_at<#{to}
          AND (p.status IS NULL OR p.status NOT IN ('APPLIED','DUPLICATE'))
        """)
    List<LocalDate> incompleteDates(@Param("userId") Long userId,
            @Param("from") LocalDateTime from,@Param("to") LocalDateTime to);

    @Select("SELECT EXISTS(SELECT 1 FROM t_token_usage WHERE user_id=#{userId} AND request_started_at IS NULL)")
    boolean hasUndated(Long userId);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM t_personal_usage_projection WHERE user_id=#{userId}
          AND status='FAILED' AND last_error_code='LOGICAL_CONFLICT')
        """)
    boolean hasConflict(@Param("userId") Long userId);

    @Select("""
        SELECT MIN(create_time) FROM t_personal_usage_projection WHERE user_id=#{userId}
          AND status IN ('PENDING','FAILED')
        """)
    LocalDateTime oldest(@Param("userId") Long userId);

    /** 反连接每轮从头补漏，不能用自增水位假设事务提交顺序。 */
    @Select("""
        SELECT u.* FROM t_token_usage u JOIN t_user owner ON owner.user_id=u.user_id AND owner.status=1 AND owner.del_flag=0
        WHERE NOT EXISTS(SELECT 1 FROM t_personal_usage_projection p
          WHERE p.user_id=u.user_id AND p.usage_id=u.id)
        ORDER BY u.id LIMIT 100
        """)
    List<TokenUsageDO> missingWork();

}
