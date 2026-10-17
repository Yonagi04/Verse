package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CostBudgetInvocationMapper extends BaseMapper<CostBudgetInvocationDO> {
    @Select("SELECT * FROM t_cost_budget_invocation WHERE request_id=#{requestId} FOR UPDATE")
    CostBudgetInvocationDO lockRequest(@Param("requestId") String requestId);
    @Select("SELECT * FROM t_cost_budget_invocation WHERE tenant_id=#{tenantId} AND api_key_id=#{keyId} "
            + "AND request_started_at >= #{start} AND state NOT IN ('SETTLED','NO_UPSTREAM') ORDER BY id LIMIT 1001 FOR UPDATE")
    List<CostBudgetInvocationDO> unfinished(@Param("tenantId") Long tenantId, @Param("keyId") Long keyId,
                                              @Param("start") LocalDateTime start);
    @Select("SELECT * FROM t_cost_budget_invocation WHERE tenant_id=#{tenantId} AND request_started_at >= #{start} "
            + "AND state NOT IN ('SETTLED','NO_UPSTREAM') ORDER BY id LIMIT 1001")
    List<CostBudgetInvocationDO> tenantUnfinished(@Param("tenantId") Long tenantId, @Param("start") LocalDateTime start);
    @Select("SELECT COUNT(*) FROM t_cost_budget_invocation i WHERE i.tenant_id=#{tenantId} AND i.request_started_at >= #{start} "
            + "AND i.state NOT IN ('SETTLED','NO_UPSTREAM') AND NOT EXISTS "
            + "(SELECT 1 FROM t_cost_budget_invocation_model m WHERE m.request_id=i.request_id)")
    long unattributed(@Param("tenantId") Long tenantId, @Param("start") LocalDateTime start);
    @Select("SELECT * FROM t_cost_budget_invocation WHERE state IN ('RUNNING','UNKNOWN') ORDER BY update_time LIMIT 100")
    List<CostBudgetInvocationDO> recoverable();
}
