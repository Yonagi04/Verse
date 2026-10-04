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
            + "AND request_started_at >= #{start} AND state NOT IN ('SETTLED','NO_UPSTREAM') ORDER BY id FOR UPDATE")
    List<CostBudgetInvocationDO> unfinished(@Param("tenantId") Long tenantId, @Param("keyId") Long keyId,
                                              @Param("start") LocalDateTime start);
    @Select("SELECT * FROM t_cost_budget_invocation WHERE state IN ('RUNNING','UNKNOWN') ORDER BY update_time LIMIT 100")
    List<CostBudgetInvocationDO> recoverable();
}
