package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationWorkDO;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CostReconciliationWorkMapper extends BaseMapper<CostReconciliationWorkDO> {
    @Select("""
            SELECT id,request_id,tenant_id,user_id,generation,next_action_at,scan_id,claim_version,
                   auto_queued,pending_confirmed,request_started_at
            FROM t_cost_reconciliation_work WHERE request_id=#{requestId} FOR UPDATE
            """)
    CostReconciliationWorkDO lockRequest(@Param("requestId") String requestId);

    @Select("""
            SELECT id,request_id,tenant_id,user_id,generation,next_action_at,scan_id,claim_version,
                   auto_queued,pending_confirmed,request_started_at
            FROM t_cost_reconciliation_work
            WHERE generation=#{generation} AND scan_id IS NULL AND auto_queued=0 AND next_action_at <= #{cutoff}
            ORDER BY next_action_at,id LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<CostReconciliationWorkDO> due(@Param("generation") Long generation, @Param("cutoff") LocalDateTime cutoff,
                                       @Param("limit") int limit);

    @Select("""
            SELECT id,request_id,tenant_id,user_id,generation,next_action_at,scan_id,claim_version,
                   auto_queued,pending_confirmed,request_started_at
            FROM t_cost_reconciliation_work
            WHERE generation=#{generation} AND scan_id=#{scanId} AND auto_queued=0 AND next_action_at <= #{now}
            ORDER BY next_action_at,id LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<CostReconciliationWorkDO> expired(@Param("generation") Long generation, @Param("scanId") Long scanId,
                                           @Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 封存只等待已绑定本轮的工作，其他到期项留给后续轮次。 */
    @Select("""
            SELECT EXISTS (SELECT 1 FROM t_cost_reconciliation_work
            WHERE generation=#{generation} AND scan_id=#{scanId})
            """)
    boolean hasUnclassified(@Param("generation") Long generation, @Param("scanId") Long scanId);

    @Update("""
            UPDATE t_cost_reconciliation_work SET scan_id=NULL, next_action_at=#{nextActionAt}
            WHERE id=#{id} AND claim_version=#{version}
            """)
    int defer(@Param("id") Long id, @Param("version") Long version, @Param("nextActionAt") LocalDateTime nextActionAt);

    @Update("""
            UPDATE t_cost_reconciliation_work SET scan_id=NULL,auto_queued=1,next_action_at=#{deadline}
            WHERE id=#{id} AND claim_version=#{version}
            """)
    int queueAutomatic(@Param("id") Long id, @Param("version") Long version, @Param("deadline") LocalDateTime deadline);

    @Select("""
            SELECT id,request_id,tenant_id,user_id,generation,next_action_at,scan_id,claim_version,
                   auto_queued,pending_confirmed,request_started_at
            FROM t_cost_reconciliation_work
            WHERE generation=#{generation} AND scan_id IS NULL AND auto_queued=1 AND next_action_at<=#{now}
            ORDER BY next_action_at,id LIMIT 100
            """)
    List<CostReconciliationWorkDO> expiredAutomatic(@Param("generation") Long generation, @Param("now") LocalDateTime now);

    @Select("""
            SELECT s.id, s.event_id, s.tenant_id, s.user_id, s.api_key_id, s.service_id, s.source, s.operation,
                   s.request_id, s.request_started_at, s.cost_status, s.estimated_cost_fen, s.budget_applied
            FROM t_cost_reconciliation_work w
            JOIN t_cost_budget_invocation i ON i.request_id=w.request_id AND i.tenant_id=w.tenant_id
            JOIN t_cost_budget_settlement s ON s.event_id=i.event_id AND s.tenant_id=w.tenant_id
                AND s.request_id=w.request_id AND s.source=i.source
            WHERE w.tenant_id=#{tenantId} AND w.pending_confirmed=1 AND w.request_started_at >= #{from}
              AND w.request_started_at < #{until} AND s.budget_applied=0
            ORDER BY w.request_started_at,w.id LIMIT 1001
            """)
    List<CostBudgetSettlementDO> pendingConfirmed(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from,
                                                    @Param("until") LocalDateTime until);
}
