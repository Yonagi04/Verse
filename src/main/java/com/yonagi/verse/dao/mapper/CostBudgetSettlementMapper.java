package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CostBudgetSettlementMapper extends BaseMapper<CostBudgetSettlementDO> {
    @Select("SELECT * FROM t_cost_budget_settlement WHERE event_id=#{eventId} FOR UPDATE")
    CostBudgetSettlementDO lockEvent(@Param("eventId") String eventId);
    @Select("SELECT * FROM t_cost_budget_settlement WHERE budget_applied=0 ORDER BY id LIMIT 100")
    List<CostBudgetSettlementDO> pending();
    @Select("SELECT * FROM t_cost_budget_settlement WHERE tenant_id=#{tenantId} AND api_key_id=#{keyId} "
            + "AND request_started_at >= #{start} ORDER BY id")
    List<CostBudgetSettlementDO> history(@Param("tenantId") Long tenantId, @Param("keyId") Long keyId,
                                          @Param("start") LocalDateTime start);
}
