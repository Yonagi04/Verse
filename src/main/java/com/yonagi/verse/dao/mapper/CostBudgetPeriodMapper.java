package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostBudgetPeriodDO;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface CostBudgetPeriodMapper extends BaseMapper<CostBudgetPeriodDO> {
    /** 锁定读取，不能使用默认隔离级别的旧快照。 */
    @Select("SELECT * FROM t_cost_budget_period WHERE tenant_id=#{tenantId} AND scope_type='API_KEY' "
            + "AND scope_id=#{keyId} AND period_type=#{type} AND period_start=#{start} FOR UPDATE")
    CostBudgetPeriodDO lockPeriod(@Param("tenantId") Long tenantId, @Param("keyId") Long keyId,
                                    @Param("type") String type, @Param("start") LocalDateTime start);
}
