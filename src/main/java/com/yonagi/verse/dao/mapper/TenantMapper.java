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
    @Select("SELECT tenant_id FROM t_tenant WHERE owner_id=#{userId} AND type='TEAM' "
            + "AND status=1 AND del_flag=0 ORDER BY tenant_id")
    List<Long> selectActiveOwnedTeamTenantIds(@Param("userId") Long userId);

    /** 按主键锁定并复核所有权，调用方按租户 ID 顺序获取锁。 */
    @Select("SELECT tenant_id FROM t_tenant WHERE tenant_id=#{tenantId} AND owner_id=#{userId} "
            + "AND type='TEAM' AND status=1 AND del_flag=0 FOR UPDATE")
    Long lockActiveOwnedTeamTenantForClosure(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

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

    /** 在持有租户行锁时停用租户。 */
    @Update("UPDATE t_tenant SET status = 0 WHERE tenant_id = #{tenantId} AND status = 1 AND del_flag = 0")
    int disableTenant(@Param("tenantId") Long tenantId);
}
