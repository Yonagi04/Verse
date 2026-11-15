package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CostReconciliationAttemptMapper extends BaseMapper<CostReconciliationAttemptDO> {
    @Select("""
            SELECT * FROM t_cost_reconciliation_attempt WHERE request_id=#{requestId}
            ORDER BY attempt_no DESC LIMIT 1
            """)
    CostReconciliationAttemptDO latest(@Param("requestId") String requestId);
}
