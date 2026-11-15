package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CostBudgetInvocationMapper extends BaseMapper<CostBudgetInvocationDO> {
    String COLUMNS = "id,tenant_id,api_key_id,source,request_id,event_id,request_started_at,owner_id,"
            + "owner_generation,state,lease_until,last_heartbeat_at,last_error_code,failure_stage,failure_code,"
            + "update_time,original_user_id,reconciliation_generation,reconciliation_disposition";
    @Select("SELECT " + COLUMNS + " FROM t_cost_budget_invocation WHERE request_id=#{requestId} FOR UPDATE")
    CostBudgetInvocationDO lockRequest(@Param("requestId") String requestId);
    @Select("SELECT " + COLUMNS + " FROM t_cost_budget_invocation WHERE tenant_id=#{tenantId} AND api_key_id=#{keyId} "
            + "AND request_started_at >= #{start} AND state NOT IN ('SETTLED','NO_UPSTREAM') ORDER BY id LIMIT 1001 FOR UPDATE")
    List<CostBudgetInvocationDO> unfinished(@Param("tenantId") Long tenantId, @Param("keyId") Long keyId,
                                              @Param("start") LocalDateTime start);
    @Select("SELECT " + COLUMNS + " FROM t_cost_budget_invocation WHERE tenant_id=#{tenantId} AND request_started_at >= #{start} "
            + "AND state NOT IN ('SETTLED','NO_UPSTREAM') ORDER BY id LIMIT 1001")
    List<CostBudgetInvocationDO> tenantUnfinished(@Param("tenantId") Long tenantId, @Param("start") LocalDateTime start);
    @Select("SELECT COUNT(*) FROM t_cost_budget_invocation i WHERE i.tenant_id=#{tenantId} AND i.request_started_at >= #{start} "
            + "AND i.state NOT IN ('SETTLED','NO_UPSTREAM') AND NOT EXISTS "
            + "(SELECT 1 FROM t_cost_budget_invocation_model m WHERE m.request_id=i.request_id)")
    long unattributed(@Param("tenantId") Long tenantId, @Param("start") LocalDateTime start);
    @Select("SELECT " + COLUMNS + " FROM t_cost_budget_invocation WHERE state IN ('RUNNING','UNKNOWN') ORDER BY update_time LIMIT 100")
    List<CostBudgetInvocationDO> recoverable();

    @Select("SELECT " + COLUMNS + " FROM t_cost_budget_invocation "
            + "WHERE reconciliation_generation=0 AND state IN ('RUNNING','UNKNOWN') ORDER BY update_time LIMIT 100")
    List<CostBudgetInvocationDO> legacyRecoverable();

    @Select("SELECT MIN(tenant_id) FROM t_cost_budget_invocation WHERE reconciliation_generation=#{generation} "
            + "AND reconciliation_disposition='TRACKING' AND tenant_id>#{after} "
            + "AND (lease_until IS NULL OR lease_until<=#{cutoff})")
    Long nextCandidateTenant(@Param("generation") Long generation, @Param("after") Long after,
                              @Param("cutoff") LocalDateTime cutoff);

    @Select("""
            <script>SELECT
            """ + COLUMNS + """
             FROM t_cost_budget_invocation
            WHERE reconciliation_generation=#{generation} AND reconciliation_disposition='TRACKING'
              AND tenant_id=#{tenantId} AND (lease_until IS NULL OR lease_until &lt;= #{cutoff})
            <if test='cursor != null'>
              <choose>
                <when test='cursor.cursorAt == null'>
                  AND ((lease_until IS NULL AND id>#{cursor.cursorId}) OR lease_until IS NOT NULL)
                </when>
                <otherwise>
                  AND (lease_until>#{cursor.cursorAt} OR (lease_until=#{cursor.cursorAt} AND id>#{cursor.cursorId}))
                </otherwise>
              </choose>
            </if>
            ORDER BY lease_until,id LIMIT 100</script>
            """)
    List<CostBudgetInvocationDO> candidatePage(@Param("generation") Long generation,
            @Param("tenantId") Long tenantId, @Param("cutoff") LocalDateTime cutoff,
            @Param("cursor") com.yonagi.verse.dao.entity.CostReconciliationTaskDO cursor);
}
