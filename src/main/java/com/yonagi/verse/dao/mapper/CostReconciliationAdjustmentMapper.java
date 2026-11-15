package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationAdjustmentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CostReconciliationAdjustmentMapper extends BaseMapper<CostReconciliationAdjustmentDO> {
    @Select("""
            SELECT id,case_id,attempt_id,service_id,operation,version,submission_key,fingerprint,actor_id,
                   cost_status,cost_fen,usage_json,created_at
            FROM t_cost_reconciliation_adjustment WHERE case_id=#{caseId} ORDER BY version DESC LIMIT 1
            """)
    CostReconciliationAdjustmentDO latest(@Param("caseId") Long caseId);
}
