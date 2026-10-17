package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TenantDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;
import java.util.List;

/**
 * 租户 Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface TenantMapper extends BaseMapper<TenantDO> {

    @Select("SELECT COUNT(*) AS total,COALESCE(SUM(u.phone IS NULL OR u.phone=''),0) AS missingPhoneCount,"
            + "COALESCE(SUM(u.email IS NULL OR u.email=''),0) AS missingEmailCount FROM t_user u JOIN t_user_tenant m ON m.user_id=u.user_id "
            + "WHERE m.tenant_id=#{tenantId} AND m.left_at IS NULL AND u.status=1 AND u.del_flag=0 AND m.role IN ('ADMIN','SUPER_ADMIN')")
    com.yonagi.verse.dto.resp.CostNotificationReadinessRespDTO.Administrators administratorContacts(@Param("tenantId") Long tenantId);
    @Select("SELECT m.role FROM t_user_tenant m JOIN t_user u ON u.user_id=m.user_id "
            + "WHERE m.tenant_id=#{tenantId} AND m.user_id=#{userId} AND m.left_at IS NULL AND u.status=1 AND u.del_flag=0 LIMIT 1")
    String recipientRole(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /** 所有者与超级管理员在同一事务中交接，旧所有者不匹配时拒绝写入。 */
    @Update("""
            UPDATE t_tenant SET owner_id = #{targetId}
            WHERE tenant_id = #{tenantId} AND owner_id = #{operatorId}
              AND type = 'TEAM' AND status = 1 AND del_flag = 0
            """)
    int transferOwner(@Param("tenantId") Long tenantId,
                      @Param("operatorId") Long operatorId, @Param("targetId") Long targetId);

    /** 两次复核之间可能有其他事务提交，不能复用 MyBatis 会话内的旧候选集合。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT tenant_id FROM t_tenant WHERE owner_id=#{userId} AND type IN ('PERSONAL','TEAM') "
            + "AND (status<>0 OR del_flag<>1) ORDER BY tenant_id")
    List<Long> selectOwnedTenantIdsForClosure(@Param("userId") Long userId);

    /** 按主键锁定并复核所有权，调用方按租户 ID 顺序获取锁。 */
    @Select("SELECT tenant_id FROM t_tenant FORCE INDEX (uk_tenant_id) WHERE tenant_id=#{tenantId} AND owner_id=#{userId} "
            + "AND type IN ('PERSONAL','TEAM') AND (status<>0 OR del_flag<>1) FOR UPDATE")
    Long lockOwnedTenantForClosure(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /** 同步注销只更新提前锁定的租户，按唯一键定位，不能在用户锁后扫描并锁住其他租户。 */
    @Update("""
            UPDATE t_tenant t FORCE INDEX (uk_tenant_id) SET status=0,del_flag=1
            WHERE t.tenant_id=#{tenantId} AND t.owner_id=#{userId} AND (t.status<>0 OR t.del_flag<>1)
              AND (t.type='PERSONAL' OR (t.type='TEAM' AND NOT EXISTS (
                  SELECT 1 FROM t_user_tenant m
                  WHERE m.tenant_id=t.tenant_id AND m.user_id<>#{userId} AND m.left_at IS NULL
              )))
              AND EXISTS (SELECT 1 FROM t_user WHERE user_id=#{userId} AND status=2 AND del_flag=1)
            """)
    int deleteClosedUsersLockedTenant(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /** 排除注销用户后仍有未退出成员的团队必须保留，兼容成员退出清理已经完成的重试。 */
    @Update("""
            UPDATE t_tenant t SET status=0,del_flag=1
            WHERE t.owner_id=#{userId} AND (t.status<>0 OR t.del_flag<>1)
              AND (t.type='PERSONAL' OR (t.type='TEAM' AND NOT EXISTS (
                  SELECT 1 FROM t_user_tenant m
                  WHERE m.tenant_id=t.tenant_id AND m.user_id<>#{userId} AND m.left_at IS NULL
              )))
              AND EXISTS (SELECT 1 FROM t_user WHERE user_id=#{userId} AND status=2 AND del_flag=1)
            """)
    int deleteClosedUsersPersonalAndSoleMemberTenants(@Param("userId") Long userId);

    /** 锁定启用租户行，作为租户状态写事务的第一把锁。 */
    @Select("SELECT * FROM t_tenant WHERE tenant_id = #{tenantId} AND status = 1 AND del_flag = 0 FOR UPDATE")
    TenantDO selectActiveTenantForUpdate(@Param("tenantId") Long tenantId);

    /** 权限读取先锁租户，与租户状态写入互斥，再按用户、成员顺序获取共享锁。 */
    @Select("SELECT * FROM t_tenant WHERE tenant_id = #{tenantId} AND status = 1 AND del_flag = 0 FOR SHARE")
    TenantDO selectActiveTenantForShare(@Param("tenantId") Long tenantId);

    /** 在持有租户行锁时停用租户。 */
    @Update("UPDATE t_tenant SET status = 0 WHERE tenant_id = #{tenantId} AND status = 1 AND del_flag = 0")
    int disableTenant(@Param("tenantId") Long tenantId);
}
