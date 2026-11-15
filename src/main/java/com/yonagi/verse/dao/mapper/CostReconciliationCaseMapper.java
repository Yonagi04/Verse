package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.projection.PendingCostReconciliation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CostReconciliationCaseMapper extends BaseMapper<CostReconciliationCaseDO> {
    String COLUMNS = "id,batch_id,tenant_id,request_id,event_id,api_key_id,source,request_started_at,attempt_id,"
            + "user_id,state,reason,version,estimated_cost_fen,review_required,resolved_at,automatic_deadline_at";

    @Select("SELECT MIN(tenant_id) FROM t_cost_reconciliation_case WHERE state='AUTO' "
            + "AND tenant_id>#{after} AND automatic_deadline_at<=#{cutoff}")
    Long nextExpiredTenant(@Param("after") Long after, @Param("cutoff") LocalDateTime cutoff);

    @Select("""
            <script>SELECT
            """ + COLUMNS + """
             FROM t_cost_reconciliation_case WHERE state='AUTO' AND tenant_id=#{tenantId}
              AND automatic_deadline_at &lt;= #{cutoff}
            <if test='cursor != null'>
              AND (automatic_deadline_at>#{cursor.cursorAt}
                OR (automatic_deadline_at=#{cursor.cursorAt} AND id>#{cursor.cursorId}))
            </if>
            ORDER BY automatic_deadline_at,id LIMIT 100</script>
            """)
    List<CostReconciliationCaseDO> expiredPage(@Param("tenantId") Long tenantId,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("cursor") com.yonagi.verse.dao.entity.CostReconciliationTaskDO cursor);
    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_case WHERE tenant_id=#{tenantId} AND request_id=#{requestId} FOR UPDATE")
    CostReconciliationCaseDO lockRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);

    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_case WHERE batch_id=#{batchId} AND id=#{id} FOR UPDATE")
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
