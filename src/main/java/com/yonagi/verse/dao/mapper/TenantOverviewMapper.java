package com.yonagi.verse.dao.mapper;

import com.yonagi.verse.dao.projection.TenantOverviewActivityRow;
import com.yonagi.verse.dao.projection.TenantOverviewCountRow;
import com.yonagi.verse.dao.projection.TenantOverviewUsageRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 已授权租户集合上的固定数量批量聚合查询。 */
@Mapper
public interface TenantOverviewMapper {
    @Select("""
            <script>SELECT tenant_id AS tenantId, COUNT(*) AS total
            FROM t_user_tenant WHERE left_at IS NULL AND tenant_id IN
            <foreach collection='tenantIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            GROUP BY tenant_id</script>
            """)
    List<TenantOverviewCountRow> memberCounts(@Param("tenantIds") List<Long> tenantIds);

    @Select("""
            <script>SELECT tenant_id AS tenantId, COUNT(*) AS total
            FROM t_llm_service WHERE status = 1 AND del_flag = 0 AND tenant_id IN
            <foreach collection='tenantIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            GROUP BY tenant_id</script>
            """)
    List<TenantOverviewCountRow> availableServiceCounts(@Param("tenantIds") List<Long> tenantIds);

    @Select("""
            <script>SELECT tenant_id AS tenantId, SUM(total_tokens) AS totalTokens,
            SUM(success_request_count) AS requestCount,
            SUM(exact_usage_count) AS exactUsageCount,
            SUM(estimated_usage_count) AS estimatedUsageCount,
            SUM(unknown_usage_count) AS unknownUsageCount,
            MAX(update_time) AS maxUpdatedAt
            FROM t_token_usage_hourly_agg WHERE tenant_id IN
            <foreach collection='tenantIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            AND bucket_start &gt;= #{from} AND bucket_start &lt; #{to}
            <if test='userId != null'>AND user_id = #{userId}</if>
            GROUP BY tenant_id</script>
            """)
    List<TenantOverviewUsageRow> usage(@Param("tenantIds") List<Long> tenantIds,
            @Param("userId") Long userId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Select("""
            <script>SELECT tenant_id AS tenantId, COUNT(*) AS total
            FROM t_tenant_join_request WHERE status = 'PENDING' AND tenant_id IN
            <foreach collection='tenantIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            GROUP BY tenant_id</script>
            """)
    List<TenantOverviewCountRow> pendingJoinCounts(@Param("tenantIds") List<Long> tenantIds);

    @Select("""
            <script>SELECT activity_type AS type, occurred_at AS occurredAt
            FROM t_tenant_activity_log WHERE tenant_id = #{tenantId} AND activity_type IN
            <foreach collection='types' item='type' open='(' separator=',' close=')'>#{type}</foreach>
            ORDER BY occurred_at DESC, id DESC LIMIT 3</script>
            """)
    List<TenantOverviewActivityRow> recentActivities(@Param("tenantId") Long tenantId,
            @Param("types") List<String> types);
}
