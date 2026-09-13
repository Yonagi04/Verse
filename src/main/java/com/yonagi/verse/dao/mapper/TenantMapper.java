package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TenantDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 租户 Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface TenantMapper extends BaseMapper<TenantDO> {

    /** 锁定启用租户行，作为租户状态写事务的第一把锁。 */
    @Select("SELECT * FROM t_tenant WHERE tenant_id = #{tenantId} AND status = 1 AND del_flag = 0 FOR UPDATE")
    TenantDO selectActiveTenantForUpdate(@Param("tenantId") Long tenantId);

    /** 在持有租户行锁时停用租户。 */
    @Update("UPDATE t_tenant SET status = 0 WHERE tenant_id = #{tenantId} AND status = 1 AND del_flag = 0")
    int disableTenant(@Param("tenantId") Long tenantId);
}
