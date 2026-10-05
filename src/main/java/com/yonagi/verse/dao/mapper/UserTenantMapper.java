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

    /** 仅有其他未退出成员的启用团队需要交接，停用或仅本人在内的团队不阻断注销。 */
    @Select("""
            SELECT t.name FROM t_user_tenant ut
            JOIN t_tenant t ON t.tenant_id=ut.tenant_id AND t.type='TEAM' AND t.status=1 AND t.del_flag=0
            WHERE ut.user_id=#{userId} AND ut.left_at IS NULL AND ut.role='SUPER_ADMIN'
              AND EXISTS (
                  SELECT 1 FROM t_user_tenant other_ut
                  WHERE other_ut.tenant_id=ut.tenant_id AND other_ut.user_id<>ut.user_id
                    AND other_ut.left_at IS NULL
              )
            ORDER BY t.tenant_id
            """)
    List<String> selectUntransferredTeamTenantNames(@Param("userId") Long userId);

    /** 调用方持有租户和双方成员行锁；同时交换角色，任一前置角色变化时由事务回滚。 */
    @Update("""
            UPDATE t_user_tenant
            SET role = CASE WHEN user_id = #{operatorId} THEN 'ADMIN' ELSE 'SUPER_ADMIN' END
            WHERE tenant_id = #{tenantId} AND left_at IS NULL
              AND ((user_id = #{operatorId} AND role = 'SUPER_ADMIN')
                OR (user_id = #{targetId} AND role = 'ADMIN'))
            """)
    int transferSuperAdminRoles(@Param("tenantId") Long tenantId,
                                @Param("operatorId") Long operatorId, @Param("targetId") Long targetId);

    /** 先锁租户，再锁用户，避免与租户成员写入的锁顺序相反。 */
    @Select("SELECT tenant_id FROM t_tenant WHERE tenant_id=#{tenantId} AND status=1 AND del_flag=0 FOR UPDATE")
    Long lockActiveJoiningTenant(@Param("tenantId") Long tenantId);

    @Select("SELECT user_id FROM t_user WHERE user_id=#{userId} AND status=1 AND del_flag=0 FOR UPDATE")
    Long lockActiveJoiningUser(@Param("userId") Long userId);

    @Update("UPDATE t_user_tenant SET left_at=CURRENT_TIMESTAMP,favorite=0,pinned=0 "
            + "WHERE user_id=#{userId} AND left_at IS NULL "
            + "AND EXISTS (SELECT 1 FROM t_user WHERE user_id=#{userId} AND status=2 AND del_flag=1)")
    int leaveClosedUsersTenants(@Param("userId") Long userId);

    /** 批量概览缓存键包含有效租户与实时角色，移除成员或停用租户后不能复用旧摘要。 */
    @Select("""
            SELECT ut.user_id, ut.tenant_id, ut.role FROM t_user_tenant ut
            JOIN t_tenant t ON t.tenant_id = ut.tenant_id AND t.status = 1 AND t.del_flag = 0
            WHERE ut.user_id = #{userId} AND ut.left_at IS NULL
              AND ut.role IN ('SUPER_ADMIN', 'ADMIN', 'MEMBER')
            ORDER BY ut.tenant_id
            """)
    List<UserTenantDO> selectOverviewMemberships(@Param("userId") Long userId);

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

    /** 从数据库读取目标有效成员关系和实时角色，不使用成员缓存。 */
    @Select("""
            SELECT ut.* FROM t_user_tenant ut
            JOIN t_tenant t ON t.tenant_id = ut.tenant_id
             AND t.status = 1 AND t.del_flag = 0
            WHERE ut.user_id = #{userId} AND ut.tenant_id = #{tenantId}
              AND ut.left_at IS NULL
            """)
    UserTenantDO selectActiveMembership(@Param("userId") Long userId,
                                        @Param("tenantId") Long tenantId);

    /** 仅更新本人仍有效的成员关系偏好；目标租户停用后不会写入。 */
    @Update("""
            UPDATE t_user_tenant ut JOIN t_tenant t ON t.tenant_id = ut.tenant_id
             AND t.status = 1 AND t.del_flag = 0
            SET ut.favorite = #{favorite}, ut.pinned = #{pinned}
            WHERE ut.user_id = #{userId} AND ut.tenant_id = #{tenantId}
              AND ut.left_at IS NULL
            """)
    int updatePreference(@Param("userId") Long userId, @Param("tenantId") Long tenantId,
                         @Param("favorite") boolean favorite, @Param("pinned") boolean pinned);

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
            UPDATE t_user_tenant SET left_at = CURRENT_TIMESTAMP, favorite = 0, pinned = 0
            WHERE user_id = #{userId} AND tenant_id = #{tenantId} AND left_at IS NULL
            """)
    int markMembershipLeft(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
