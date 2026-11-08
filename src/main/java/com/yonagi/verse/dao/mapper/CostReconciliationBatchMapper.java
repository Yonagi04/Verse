package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CostReconciliationBatchMapper extends BaseMapper<CostReconciliationBatchDO> {
    @Select("""
            SELECT b.* FROM t_cost_reconciliation_batch b
            JOIN t_cost_reconciliation_scan s ON s.id=b.scan_id AND s.sealed_at IS NOT NULL
            WHERE b.notified_at IS NULL AND NOT EXISTS (
                SELECT 1 FROM t_cost_reconciliation_case c WHERE c.batch_id=b.id AND c.state='AUTO'
            ) ORDER BY b.id LIMIT 100
            """)
    List<CostReconciliationBatchDO> awaitingNotification();

    @Select("SELECT * FROM t_cost_reconciliation_batch WHERE id=#{id} FOR UPDATE")
    CostReconciliationBatchDO lock(@Param("id") Long id);
}
