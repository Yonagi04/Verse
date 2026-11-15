package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationTaskDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 页面清单读取与屏障；不存在工作抢占或待执行任务查询。 */
public interface CostReconciliationTaskMapper extends BaseMapper<CostReconciliationTaskDO> {
    String COLUMNS = "id,task_id,scan_id,page_sequence,tenant_id,operation,payload_hash,item_count,"
            + "execution_deadline_at,cursor_at,cursor_id,outcome,reason,created_at,finished_at";

    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_task WHERE task_id=#{taskId} FOR SHARE")
    CostReconciliationTaskDO share(@Param("taskId") String taskId);

    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_task WHERE task_id=#{taskId} FOR UPDATE")
    CostReconciliationTaskDO lock(@Param("taskId") String taskId);

    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_task "
            + "WHERE scan_id=#{runId} AND page_sequence=#{sequence}")
    CostReconciliationTaskDO bySequence(@Param("runId") Long runId, @Param("sequence") Long sequence);

    @Select("SELECT " + COLUMNS + " FROM t_cost_reconciliation_task WHERE scan_id=#{runId} "
            + "AND tenant_id=#{tenantId} AND operation=#{operation} ORDER BY page_sequence DESC LIMIT 1")
    CostReconciliationTaskDO lastPage(@Param("runId") Long runId, @Param("tenantId") Long tenantId,
                                      @Param("operation") String operation);

    @Select("SELECT EXISTS(SELECT 1 FROM t_cost_reconciliation_task WHERE scan_id=#{runId} AND outcome='PENDING')")
    boolean hasPending(@Param("runId") Long runId);

    @Select("SELECT COUNT(*) FROM t_cost_reconciliation_task WHERE scan_id=#{runId}")
    long countAll(@Param("runId") Long runId);

    @Select("SELECT COUNT(*) FROM t_cost_reconciliation_task WHERE scan_id=#{runId} AND outcome <> 'DONE'")
    long countUnsuccessful(@Param("runId") Long runId);

    /** 仅限到期清理的清单身份，不提供业务执行成员。 */
    @Select("SELECT task_id FROM t_cost_reconciliation_task WHERE scan_id=#{runId} "
            + "AND outcome='PENDING' ORDER BY id LIMIT 100")
    List<String> pendingForExpiry(@Param("runId") Long runId);
}
