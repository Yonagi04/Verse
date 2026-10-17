package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TenantCostBudgetDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TenantCostBudgetMapper extends BaseMapper<TenantCostBudgetDO> {
    @Select("SELECT * FROM t_tenant_cost_budget WHERE tenant_id=#{tenantId} FOR UPDATE")
    TenantCostBudgetDO lock(@Param("tenantId") Long tenantId);
}
