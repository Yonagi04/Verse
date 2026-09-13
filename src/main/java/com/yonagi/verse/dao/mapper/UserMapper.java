package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.projection.CurrentTenantState;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/05/18 19:40
 */

public interface UserMapper extends BaseMapper<UserDO> {

    /** 查询用户保存值及其对应的有效当前租户。 */
    @Select("""
            SELECT u.last_active_tenant_id AS storedTenantId,
                   t.tenant_id AS tenantId, t.name, t.type, ut.role
            FROM t_user u
            LEFT JOIN t_tenant t
              ON t.tenant_id = u.last_active_tenant_id
             AND t.status = 1 AND t.del_flag = 0
            LEFT JOIN t_user_tenant ut
              ON ut.user_id = u.user_id AND ut.tenant_id = t.tenant_id
             AND ut.left_at IS NULL
             AND ut.role IN ('SUPER_ADMIN', 'ADMIN', 'MEMBER')
            WHERE u.user_id = #{userId} AND u.status = 1 AND u.del_flag = 0
            """)
    CurrentTenantState selectCurrentTenantState(@Param("userId") Long userId);

    /** 锁定用户行，固定租户状态变更的最后加锁顺序。 */
    @Select("SELECT * FROM t_user WHERE user_id = #{userId} AND status = 1 AND del_flag = 0 FOR UPDATE")
    UserDO selectActiveUserForUpdate(@Param("userId") Long userId);

    /** 基于旧值执行 compare-and-set，避免自动修复覆盖并发切换。 */
    @Update("""
            <script>
            UPDATE t_user SET last_active_tenant_id = #{newTenantId}
            WHERE user_id = #{userId} AND status = 1 AND del_flag = 0
            <choose>
              <when test="expectedTenantId == null">AND last_active_tenant_id IS NULL</when>
              <otherwise>AND last_active_tenant_id = #{expectedTenantId}</otherwise>
            </choose>
            </script>
            """)
    int compareAndSetCurrentTenant(@Param("userId") Long userId,
                                   @Param("expectedTenantId") Long expectedTenantId,
                                   @Param("newTenantId") Long newTenantId);

    /** 在持有用户行锁时写入当前租户。 */
    @Update("UPDATE t_user SET last_active_tenant_id = #{tenantId} WHERE user_id = #{userId} AND status = 1 AND del_flag = 0")
    int updateCurrentTenant(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /** 仅在目标仍为当前租户时回退单个用户。 */
    @Update("""
            UPDATE t_user u
            SET u.last_active_tenant_id = (
                SELECT fallback.tenant_id FROM (
                    SELECT t.tenant_id
                    FROM t_tenant t
                    JOIN t_user_tenant ut ON ut.tenant_id = t.tenant_id
                    WHERE ut.user_id = #{userId} AND ut.left_at IS NULL
                      AND ut.role IN ('SUPER_ADMIN', 'ADMIN', 'MEMBER')
                      AND t.owner_id = #{userId} AND t.type = 'PERSONAL'
                      AND t.status = 1 AND t.del_flag = 0
                    ORDER BY t.tenant_id LIMIT 1
                ) fallback
            )
            WHERE u.user_id = #{userId} AND u.last_active_tenant_id = #{closedTenantId}
              AND u.status = 1 AND u.del_flag = 0
            """)
    int fallbackCurrentTenant(@Param("userId") Long userId,
                              @Param("closedTenantId") Long closedTenantId);

    /** 租户关闭时集合式回退所有仍受影响的用户。 */
    @Update("""
            UPDATE t_user u
            LEFT JOIN (
                SELECT ut.user_id, MIN(t.tenant_id) AS tenant_id
                FROM t_user_tenant ut
                JOIN t_tenant t ON t.tenant_id = ut.tenant_id
                WHERE ut.left_at IS NULL
                  AND ut.role IN ('SUPER_ADMIN', 'ADMIN', 'MEMBER')
                  AND t.type = 'PERSONAL' AND t.status = 1 AND t.del_flag = 0
                GROUP BY ut.user_id
            ) fallback ON fallback.user_id = u.user_id
            SET u.last_active_tenant_id = fallback.tenant_id
            WHERE u.last_active_tenant_id = #{closedTenantId}
              AND u.status = 1 AND u.del_flag = 0
            """)
    int fallbackUsersFromClosedTenant(@Param("closedTenantId") Long closedTenantId);

}
