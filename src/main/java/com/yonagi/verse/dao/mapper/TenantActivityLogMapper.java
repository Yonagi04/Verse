package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TenantActivityLogDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 租户动态事实 Mapper。 */
public interface TenantActivityLogMapper extends BaseMapper<TenantActivityLogDO> {
    /** 按事件 ID 检查事实是否存在。 */
    @Select("SELECT COUNT(*) FROM t_tenant_activity_log WHERE event_id=#{eventId}")
    long countByEventId(@Param("eventId") String eventId);

    /**
     * 按租户和稳定复合游标倒序读取动态，调用方传入 limit + 1 判断是否还有下一批。
     */
    @Select("""
            <script>
            SELECT activity.id, activity.event_id, activity.tenant_id,
                   activity.category, activity.activity_type,
                   activity.actor_user_id, activity.actor_username,
                   activity.actor_nickname,
                   CASE WHEN current_membership.id IS NOT NULL
                        THEN current_actor.nickname
                        ELSE NULL
                   END AS current_actor_nickname,
                   activity.target_type, activity.target_id, activity.target_name,
                   activity.detail_json, activity.schema_version,
                   activity.occurred_at, activity.create_time
            FROM t_tenant_activity_log activity
            LEFT JOIN t_user current_actor
              ON current_actor.username = activity.actor_username
            LEFT JOIN t_user_tenant current_membership
              ON current_membership.user_id = current_actor.user_id
             AND current_membership.tenant_id = activity.tenant_id
             AND current_membership.left_at IS NULL
            WHERE activity.tenant_id = #{tenantId}
            <if test="cursorOccurredAt != null and cursorId != null">
              AND (activity.occurred_at &lt; #{cursorOccurredAt}
                   OR (activity.occurred_at = #{cursorOccurredAt}
                       AND activity.id &lt; #{cursorId}))
            </if>
            ORDER BY activity.occurred_at DESC, activity.id DESC
            LIMIT #{limitPlusOne}
            </script>
            """)
    List<TenantActivityLogDO> selectTimeline(@Param("tenantId") Long tenantId,
                                              @Param("cursorOccurredAt") LocalDateTime cursorOccurredAt,
                                              @Param("cursorId") Long cursorId,
                                              @Param("limitPlusOne") int limitPlusOne);
}
