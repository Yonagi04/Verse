package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.projection.TenantCostBudgetAggregate;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CostBudgetSettlementMapper extends BaseMapper<CostBudgetSettlementDO> {
    @Select("SELECT * FROM t_cost_budget_settlement WHERE event_id=#{eventId} FOR UPDATE")
    CostBudgetSettlementDO lockEvent(@Param("eventId") String eventId);
    // 状态按字节匹配，与 Enum.valueOf 一致，不接受表排序规则容忍的大小写或尾空格。
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN CAST(cost_status AS BINARY) = _binary'CALCULATED'
                       THEN CAST(estimated_cost_fen AS DECIMAL(65,18)) ELSE 0 END), 0) AS used_cost_fen,
                   COUNT(CASE WHEN CAST(cost_status AS BINARY) = _binary'CALCULATED' THEN 1 END) AS calculated_count,
                   COUNT(CASE WHEN CAST(cost_status AS BINARY) = _binary'UNPRICED' THEN 1 END) AS unpriced_count,
                   COUNT(CASE WHEN CAST(cost_status AS BINARY) = _binary'UNCALCULABLE' THEN 1 END) AS uncalculable_count,
                   COUNT(CASE WHEN CAST(cost_status AS BINARY) = _binary'NOT_CHARGEABLE' THEN 1 END) AS not_chargeable_count,
                   COUNT(CASE WHEN cost_status IS NULL
                       OR CAST(cost_status AS BINARY) NOT IN
                           (_binary'CALCULATED', _binary'UNPRICED', _binary'UNCALCULABLE', _binary'NOT_CHARGEABLE')
                       OR (CAST(cost_status AS BINARY) = _binary'CALCULATED' AND estimated_cost_fen IS NULL)
                       THEN 1 END) AS invalid_count
            FROM t_cost_budget_settlement
            WHERE tenant_id=#{tenantId} AND budget_applied=1
              AND request_started_at >= #{start} AND request_started_at < #{end}
            """)
    TenantCostBudgetAggregate aggregateTenant(@Param("tenantId") Long tenantId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    @Select("SELECT * FROM t_cost_budget_settlement WHERE budget_applied=0 ORDER BY id LIMIT 100")
    List<CostBudgetSettlementDO> pending();
    @Select("SELECT * FROM t_cost_budget_settlement WHERE tenant_id=#{tenantId} AND api_key_id=#{keyId} "
            + "AND request_started_at >= #{start} ORDER BY id")
    List<CostBudgetSettlementDO> history(@Param("tenantId") Long tenantId, @Param("keyId") Long keyId,
                                          @Param("start") LocalDateTime start);
    @Select("SELECT * FROM t_cost_budget_settlement WHERE tenant_id=#{tenantId} AND service_id=#{serviceId} "
            + "AND request_started_at >= #{start} ORDER BY id")
    List<CostBudgetSettlementDO> modelHistory(@Param("tenantId") Long tenantId,
            @Param("serviceId") Long serviceId, @Param("start") LocalDateTime start);
}
