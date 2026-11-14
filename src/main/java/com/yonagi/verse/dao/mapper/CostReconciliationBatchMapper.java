package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CostReconciliationBatchMapper extends BaseMapper<CostReconciliationBatchDO> {
    String COLUMNS = "id,scan_id,tenant_id,code,notified_at,recipients_json";

    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_batch WHERE scan_id=#{runId} "
            + "AND id>#{after} ORDER BY id LIMIT 100")
    List<CostReconciliationBatchDO> runPage(@Param("runId") Long runId, @Param("after") Long after);

    @Select("SELECT EXISTS(SELECT 1 FROM t_cost_reconciliation_batch b "
            + "JOIN t_cost_reconciliation_scan s ON s.id=b.scan_id AND s.state='SEALED' "
            + "WHERE b.id=#{id} AND NOT EXISTS(SELECT 1 FROM t_cost_reconciliation_case c "
            + "WHERE c.batch_id=b.id AND c.state='AUTO'))")
    boolean ready(@Param("id") Long id);
    @Select("""
            SELECT b.id,b.scan_id,b.tenant_id,b.code,b.notified_at,b.recipients_json FROM t_cost_reconciliation_batch b
            JOIN t_cost_reconciliation_scan s ON s.id=b.scan_id AND s.sealed_at IS NOT NULL
            WHERE b.notified_at IS NULL AND NOT EXISTS (
                SELECT 1 FROM t_cost_reconciliation_case c WHERE c.batch_id=b.id AND c.state='AUTO'
            ) ORDER BY b.id LIMIT 100
            """)
    List<CostReconciliationBatchDO> awaitingNotification();

    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_batch WHERE id=#{id} FOR UPDATE")
    CostReconciliationBatchDO lock(@Param("id") Long id);
}
