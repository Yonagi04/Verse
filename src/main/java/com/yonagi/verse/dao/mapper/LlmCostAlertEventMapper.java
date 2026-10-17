package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.LlmCostAlertEventDO;
import com.yonagi.verse.dao.entity.TenantDO;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface LlmCostAlertEventMapper extends BaseMapper<LlmCostAlertEventDO> {
    @Select("SELECT * FROM t_llm_cost_alert_event WHERE event_id=#{eventId} FOR UPDATE")
    LlmCostAlertEventDO lock(@Param("eventId") String eventId);
    @Select("SELECT * FROM t_tenant WHERE tenant_id=#{tenantId}")
    TenantDO tenantSnapshot(@Param("tenantId") Long tenantId);
    /** 已认领任务可能已被发送队列接管，交给外发前 guard/结果镜像，不能覆盖真实受理结果。 */
    @Update("UPDATE t_llm_cost_alert_delivery SET status='CANCELLED',reason_code='LIMIT_SUPERSEDED',update_time=#{now} "
            + "WHERE event_id IN (SELECT event_id FROM t_llm_cost_alert_event WHERE tenant_id=#{tenantId} "
            + "AND scope_type=#{scope} AND scope_id=#{scopeId} AND period_type=#{period} AND period_start=#{start} AND event_type='WARNING') "
            + "AND status IN ('PENDING','RETRY_WAIT')")
    void cancelWarning(@Param("tenantId") Long tenantId, @Param("scope") String scope, @Param("scopeId") Long scopeId,
            @Param("period") String period, @Param("start") LocalDateTime start, @Param("now") LocalDateTime now);
}
