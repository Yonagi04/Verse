package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

import java.time.LocalDateTime;

@Mapper
public interface CostReconciliationScanMapper extends BaseMapper<CostReconciliationScanDO> {
    String COLUMNS = "id,state,generation,cutoff_at,sealed_at,trigger_key,discovery_deadline_at,"
            + "execution_deadline_at,cleanup_deadline_at,producer_token,producer_lease_until,task_count,"
            + "production_closed,discovery_complete,completion_reason,control_sequence,"
            + "control_recovery_required,notification_recovery_required,notification_epoch,notification_deadline_at,last_error";
    @Select("SELECT generation FROM t_cost_reconciliation_scan WHERE guard_slot=1")
    Long activationGeneration();

    @Select("SELECT DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 8 HOUR)")
    LocalDateTime databaseTime();

    /** 仅合法触发创建运行时串行化；逐笔分类绝不获取此锁。 */
    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_scan WHERE guard_slot=1 FOR UPDATE")
    CostReconciliationScanDO lockActivation();

    /** 调用方已持有启用标记锁。 */
    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_scan WHERE guard_slot=2 FOR UPDATE")
    CostReconciliationScanDO lockActiveScan();

    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_scan WHERE id=#{id} FOR UPDATE")
    CostReconciliationScanDO lock(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_scan WHERE trigger_key=#{key}")
    CostReconciliationScanDO byTrigger(@Param("key") String key);

    @Select("SELECT id FROM t_cost_reconciliation_scan WHERE id>#{after} AND state <> 'ACTIVATION' "
            + "AND (state <> 'SEALED' OR control_recovery_required=1 OR notification_recovery_required=1) "
            + "ORDER BY id LIMIT 100")
    List<Long> recoveryPage(@Param("after") Long after);
}
