package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LlmCostBudgetMapper extends BaseMapper<LlmCostBudgetDO> {
    @Select("SELECT * FROM t_llm_cost_budget WHERE tenant_id=#{tenantId} AND service_id=#{serviceId} FOR UPDATE")
    LlmCostBudgetDO lock(@Param("tenantId") Long tenantId, @Param("serviceId") Long serviceId);

    @Select("SELECT service_id FROM t_cost_budget_invocation_model WHERE request_id=#{requestId} ORDER BY service_id")
    List<Long> participants(@Param("requestId") String requestId);

    @Select("SELECT service_id FROM t_cost_budget_invocation_model WHERE request_id=#{requestId} ORDER BY service_id FOR SHARE")
    List<Long> currentParticipants(@Param("requestId") String requestId);

    @Insert("INSERT INTO t_cost_budget_invocation_model(request_id,tenant_id,service_id,first_registered_at) "
            + "VALUES(#{requestId},#{tenantId},#{serviceId},#{now}) ON DUPLICATE KEY UPDATE request_id=request_id")
    void register(@Param("requestId") String requestId, @Param("tenantId") Long tenantId,
            @Param("serviceId") Long serviceId, @Param("now") LocalDateTime now);

    @Select("SELECT i.* FROM t_cost_budget_invocation_model m JOIN t_cost_budget_invocation i ON i.request_id=m.request_id "
            + "WHERE m.tenant_id=#{tenantId} AND m.service_id=#{serviceId} AND i.request_started_at>=#{start} "
            + "AND i.state NOT IN ('SETTLED','NO_UPSTREAM') ORDER BY i.id LIMIT 1001 FOR UPDATE")
    List<CostBudgetInvocationDO> unfinished(@Param("tenantId") Long tenantId, @Param("serviceId") Long serviceId,
            @Param("start") LocalDateTime start);

    @Select({"<script>SELECT i.*,m.service_id FROM t_cost_budget_invocation_model m JOIN t_cost_budget_invocation i ON i.request_id=m.request_id",
            "WHERE m.tenant_id=#{tenantId} AND m.service_id IN",
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
            "AND i.request_started_at &gt;= #{start} AND i.state NOT IN ('SETTLED','NO_UPSTREAM')",
            "ORDER BY m.service_id,i.id LIMIT 10001 FOR UPDATE</script>"})
    List<com.yonagi.verse.dao.projection.LlmBudgetPendingInvocation> unfinishedBatch(@Param("tenantId") Long tenantId,
            @Param("ids") List<Long> ids, @Param("start") LocalDateTime start);

    @Select("SELECT COUNT(*) AS total,COALESCE(SUM(u.phone IS NULL OR u.phone=''),0) AS missingPhoneCount,"
            + "COALESCE(SUM(u.email IS NULL OR u.email=''),0) AS missingEmailCount FROM t_user u JOIN t_user_tenant m ON m.user_id=u.user_id "
            + "WHERE m.tenant_id=#{tenantId} AND m.left_at IS NULL AND u.status=1 AND u.del_flag=0 "
            + "AND m.role IN ('ADMIN','SUPER_ADMIN')")
    com.yonagi.verse.dto.resp.CostNotificationReadinessRespDTO.Administrators administratorContacts(@Param("tenantId") Long tenantId);

    /** 调用方先持有有效租户、有效用户共享锁；此处只锁成员关系，不再通过 JOIN 隐式锁用户。 */
    @Select("SELECT role FROM t_user_tenant WHERE tenant_id=#{tenantId} AND user_id=#{userId} "
            + "AND left_at IS NULL LIMIT 1 FOR SHARE")
    String role(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}
