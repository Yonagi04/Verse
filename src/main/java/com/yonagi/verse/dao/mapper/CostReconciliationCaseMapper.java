package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.projection.PendingCostReconciliation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface CostReconciliationCaseMapper extends BaseMapper<CostReconciliationCaseDO> {
    @Select("SELECT * FROM t_cost_reconciliation_case WHERE tenant_id=#{tenantId} AND request_id=#{requestId} FOR UPDATE")
    CostReconciliationCaseDO lockRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);

    @Select("SELECT * FROM t_cost_reconciliation_case WHERE batch_id=#{batchId} AND id=#{id} FOR UPDATE")
    CostReconciliationCaseDO lockInBatch(@Param("batchId") Long batchId, @Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_cost_reconciliation_case WHERE batch_id=#{batchId} AND state=#{state}")
    long count(@Param("batchId") Long batchId, @Param("state") String state);

    @Select("""
            SELECT id FROM t_cost_reconciliation_case
            WHERE batch_id=#{batchId} AND state='PENDING' ORDER BY id LIMIT 1 FOR SHARE
            """)
    Long lockPending(@Param("batchId") Long batchId);

    @Select("""
            SELECT COUNT(*) AS pendingCount,COALESCE(SUM(estimated_cost_fen),0) AS estimatedCostFen
            FROM t_cost_reconciliation_case
            WHERE tenant_id=#{tenantId} AND request_started_at>=#{from}
              AND state IN ('PENDING','AUTO','REVIEW')
            """)
    PendingCostReconciliation pendingSummary(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from);
}
