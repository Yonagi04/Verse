package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.UserTenantDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户-租户关联 Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface UserTenantMapper extends BaseMapper<UserTenantDO> {

    /** 查询用户可回退的有效个人租户。 */
    @Select("""
            SELECT u.last_active_tenant_id AS storedTenantId,
                   t.tenant_id AS tenantId, t.name, t.type, ut.role
            FROM t_user u
            JOIN t_tenant t ON t.owner_id = u.user_id
             AND t.type = 'PERSONAL' AND t.status = 1 AND t.del_flag = 0
            JOIN t_user_tenant ut ON ut.user_id = u.user_id AND ut.tenant_id = t.tenant_id
             AND ut.left_at IS NULL
             AND ut.role IN ('SUPER_ADMIN', 'ADMIN', 'MEMBER')
            WHERE u.user_id = #{userId} AND u.status = 1 AND u.del_flag = 0
            ORDER BY t.tenant_id LIMIT 1
            """)
    com.yonagi.verse.dao.projection.CurrentTenantState selectValidPersonalTenant(@Param("userId") Long userId);

    /** 锁定目标成员关系；调用方须先锁租户行。 */
    @Select("""
            SELECT * FROM t_user_tenant
            WHERE user_id = #{userId} AND tenant_id = #{tenantId} AND left_at IS NULL
              AND role IN ('SUPER_ADMIN', 'ADMIN', 'MEMBER')
            FOR UPDATE
            """)
    UserTenantDO selectActiveMembershipForUpdate(@Param("userId") Long userId,
                                                  @Param("tenantId") Long tenantId);

    /** 原子刷新成员关系最近访问时间。 */
    @Update("""
            UPDATE t_user_tenant SET last_accessed_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND tenant_id = #{tenantId} AND left_at IS NULL
            """)
    int touchLastAccessedAt(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /** 锁定租户全部有效成员关系，供关闭租户批量回退使用。 */
    @Select("SELECT * FROM t_user_tenant WHERE tenant_id = #{tenantId} AND left_at IS NULL ORDER BY user_id FOR UPDATE")
    List<UserTenantDO> selectActiveMembershipsForUpdate(@Param("tenantId") Long tenantId);

    /** 在锁定成员关系后将其标记为已离开。 */
    @Update("""
            UPDATE t_user_tenant SET left_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND tenant_id = #{tenantId} AND left_at IS NULL
            """)
    int markMembershipLeft(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
