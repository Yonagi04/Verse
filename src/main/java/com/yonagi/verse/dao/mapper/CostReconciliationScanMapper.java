package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface CostReconciliationScanMapper extends BaseMapper<CostReconciliationScanDO> {
    @Select("SELECT generation FROM t_cost_reconciliation_scan WHERE guard_slot=1")
    Long activationGeneration();

    @Select("SELECT DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 8 HOUR)")
    LocalDateTime databaseTime();

    /** 开轮、认领和封存先锁启用标记，串行化轮次切换。 */
    @Select("""
            SELECT id,state,generation,cutoff_at,sealed_at
            FROM t_cost_reconciliation_scan WHERE guard_slot=1 FOR UPDATE
            """)
    CostReconciliationScanDO lockActivation();

    /** 调用方已持有启用标记锁。 */
    @Select("""
            SELECT id,state,generation,cutoff_at,sealed_at
            FROM t_cost_reconciliation_scan WHERE guard_slot=2 FOR UPDATE
            """)
    CostReconciliationScanDO lockActiveScan();
}
