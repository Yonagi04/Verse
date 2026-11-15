package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.dao.entity.TokenUsageHourlyAggDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
import com.yonagi.verse.dao.projection.UsageAggregateRow;
import com.yonagi.verse.dao.projection.UsageBreakdownRow;
import org.apache.ibatis.annotations.Select;

/** Token 用量小时预聚合 Mapper。 */
@Mapper
public interface TokenUsageHourlyAggMapper extends BaseMapper<TokenUsageHourlyAggDO> {
    /** 重建与注销删除共用事务锁，防止旧聚合快照在清理完成后重新写入。 */
    @Select("SELECT id FROM t_usage_projection_guard WHERE id=1 FOR UPDATE")
    Integer lockProjection();
    /** 删除待重算时间窗，随后写入绝对聚合值。 */
    @Delete("DELETE FROM t_token_usage_hourly_agg WHERE bucket_start>=#{from} AND bucket_start<#{to}")
    int deleteRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Delete("DELETE FROM t_token_usage_hourly_agg WHERE tenant_id=#{tenantId} AND bucket_start=#{hour}")
    int deleteTenantHour(@Param("tenantId") Long tenantId, @Param("hour") LocalDateTime hour);

    /** 从用量及费用事实重建时间窗。 */
    @Insert("""
        INSERT INTO t_token_usage_hourly_agg
        (tenant_id,user_id,api_key_id,service_id,model,bucket_start,input_tokens,cached_input_tokens,
         cache_write_input_tokens,output_tokens,total_tokens,request_count,success_request_count,
         exact_usage_count,estimated_usage_count,unknown_usage_count,estimated_cost_fen,calculated_count,
         unpriced_count,uncalculable_count,not_chargeable_count)
        SELECT u.tenant_id,u.user_id,u.api_key_id,u.service_id,u.model,
          DATE_FORMAT(COALESCE(u.request_started_at,u.create_time),'%Y-%m-%d %H:00:00'),
          SUM(CASE WHEN u.status='SUCCESS' THEN COALESCE(u.input_tokens,u.prompt_tokens,0) ELSE 0 END),
          SUM(CASE WHEN u.status='SUCCESS' THEN COALESCE(u.cached_input_tokens,0) ELSE 0 END),
          SUM(CASE WHEN u.status='SUCCESS' THEN COALESCE(u.cache_write_input_tokens,0) ELSE 0 END),
          SUM(CASE WHEN u.status='SUCCESS' THEN COALESCE(u.output_tokens,u.completion_tokens,0) ELSE 0 END),
          SUM(CASE WHEN u.status='SUCCESS' THEN COALESCE(u.normalized_total_tokens,u.total_tokens,0) ELSE 0 END),COUNT(*),SUM(u.status='SUCCESS'),
          SUM(u.usage_source='EXACT'),SUM(u.usage_source='ESTIMATED'),SUM(u.usage_source='UNKNOWN'),
          SUM(CASE WHEN c.cost_status='CALCULATED' THEN COALESCE(c.estimated_cost_fen,0) ELSE 0 END),
          SUM(c.cost_status='CALCULATED'),SUM(c.cost_status='UNPRICED'),SUM(c.cost_status='UNCALCULABLE'),
          SUM(c.cost_status='NOT_CHARGEABLE')
        FROM t_token_usage u JOIN t_token_usage_cost c ON c.usage_id=u.id
        JOIN t_user usr ON usr.user_id=u.user_id AND usr.status<>2 AND usr.del_flag=0
        WHERE u.source='API_KEY' AND COALESCE(u.request_started_at,u.create_time)>=#{from}
          AND COALESCE(u.request_started_at,u.create_time)<#{to}
        GROUP BY u.tenant_id,u.user_id,u.api_key_id,u.service_id,u.model,
          DATE_FORMAT(COALESCE(u.request_started_at,u.create_time),'%Y-%m-%d %H:00:00')
        """)
    int rebuildRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 更正行只贡献费用，真实请求数和 Token 始终来自原始用量。 */
    @Insert("""
        <script>
        INSERT INTO t_token_usage_hourly_agg
        (tenant_id,user_id,api_key_id,service_id,model,bucket_start,input_tokens,cached_input_tokens,
         cache_write_input_tokens,output_tokens,total_tokens,request_count,success_request_count,
         exact_usage_count,estimated_usage_count,unknown_usage_count,estimated_cost_fen,calculated_count,
         unpriced_count,uncalculable_count,not_chargeable_count)
        SELECT f.tenant_id,f.user_id,f.api_key_id,f.service_id,f.model,f.bucket_start,
               SUM(f.input_tokens),SUM(f.cached_tokens),SUM(f.cache_write_tokens),SUM(f.output_tokens),
               SUM(f.total_tokens),SUM(f.requests),SUM(f.successes),SUM(f.exact_count),SUM(f.estimated_count),
               SUM(f.unknown_count),SUM(CASE WHEN f.cost_status='CALCULATED' THEN f.cost_fen ELSE 0 END),
               SUM(CASE WHEN f.cost_status='CALCULATED' THEN 1 ELSE 0 END),
               SUM(CASE WHEN f.cost_status='UNPRICED' THEN 1 ELSE 0 END),
               SUM(CASE WHEN f.cost_status='UNCALCULABLE' THEN 1 ELSE 0 END),
               SUM(CASE WHEN f.cost_status='NOT_CHARGEABLE' THEN 1 ELSE 0 END)
        FROM (
          SELECT <if test="tenantId != null">STRAIGHT_JOIN</if>
                 u.tenant_id,u.user_id,u.api_key_id,u.service_id,u.model,
                 DATE_FORMAT(COALESCE(u.request_started_at,u.create_time),'%Y-%m-%d %H:00:00') AS bucket_start,
                 CASE WHEN u.status='SUCCESS' THEN COALESCE(u.input_tokens,u.prompt_tokens,0) ELSE 0 END AS input_tokens,
                 CASE WHEN u.status='SUCCESS' THEN COALESCE(u.cached_input_tokens,0) ELSE 0 END AS cached_tokens,
                 CASE WHEN u.status='SUCCESS' THEN COALESCE(u.cache_write_input_tokens,0) ELSE 0 END AS cache_write_tokens,
                 CASE WHEN u.status='SUCCESS' THEN COALESCE(u.output_tokens,u.completion_tokens,0) ELSE 0 END AS output_tokens,
                 CASE WHEN u.status='SUCCESS' THEN COALESCE(u.normalized_total_tokens,u.total_tokens,0) ELSE 0 END AS total_tokens,
                 1 AS requests,u.status='SUCCESS' AS successes,u.usage_source='EXACT' AS exact_count,
                 u.usage_source='ESTIMATED' AS estimated_count,u.usage_source='UNKNOWN' AS unknown_count,
                 CASE WHEN EXISTS (SELECT 1 FROM t_cost_reconciliation_adjustment a WHERE a.case_id=c.id)
                      THEN NULL ELSE original.cost_status END AS cost_status,
                 original.estimated_cost_fen AS cost_fen
          <choose>
            <when test="tenantId != null">
              FROM (
                SELECT started.id FROM t_token_usage started FORCE INDEX (idx_usage_tenant_started)
                WHERE started.tenant_id=#{tenantId} AND started.request_started_at&gt;=#{from} AND started.request_started_at&lt;#{to}
                UNION ALL
                SELECT legacy.id FROM t_token_usage legacy FORCE INDEX (idx_tenant_time)
                WHERE legacy.tenant_id=#{tenantId} AND legacy.request_started_at IS NULL
                  AND legacy.create_time&gt;=#{from} AND legacy.create_time&lt;#{to}
              ) candidates JOIN t_token_usage u ON u.id=candidates.id
            </when>
            <otherwise>FROM t_token_usage u</otherwise>
          </choose>
          JOIN t_token_usage_cost original ON original.usage_id=u.id
          JOIN t_user usr ON usr.user_id=u.user_id AND usr.status&lt;&gt;2 AND usr.del_flag=0
          LEFT JOIN t_cost_reconciliation_case c ON c.event_id=u.event_id AND c.tenant_id=u.tenant_id
            AND c.request_id=u.request_id AND c.source=u.source
          WHERE u.source='API_KEY'
          <if test="tenantId == null">
            AND COALESCE(u.request_started_at,u.create_time)&gt;=#{from}
            AND COALESCE(u.request_started_at,u.create_time)&lt;#{to}
          </if>
          UNION ALL
          SELECT c.tenant_id,COALESCE(usr.user_id,0),c.api_key_id,a.service_id,
                 COALESCE(u.model,s.model_name,CAST(a.service_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci),
                 DATE_FORMAT(c.request_started_at,'%Y-%m-%d %H:00:00'),
                 0,0,0,0,0,0,0,0,0,0,a.cost_status,a.cost_fen
          FROM t_cost_reconciliation_case c
          JOIN t_cost_reconciliation_adjustment a ON a.case_id=c.id AND a.version=(
            SELECT newest.version FROM t_cost_reconciliation_adjustment newest
            WHERE newest.case_id=c.id ORDER BY newest.version DESC LIMIT 1
          )
          LEFT JOIN t_user usr ON usr.user_id=c.user_id AND usr.status&lt;&gt;2 AND usr.del_flag=0
          LEFT JOIN t_token_usage u ON u.event_id=c.event_id AND u.tenant_id=c.tenant_id AND u.service_id=a.service_id
          LEFT JOIN t_llm_service s ON s.service_id=a.service_id AND s.tenant_id=c.tenant_id
          WHERE c.source='API_KEY' AND c.request_started_at&gt;=#{from} AND c.request_started_at&lt;#{to}
          <if test="tenantId != null">AND c.tenant_id=#{tenantId}</if>
        ) f
        GROUP BY f.tenant_id,f.user_id,f.api_key_id,f.service_id,f.model,f.bucket_start
        </script>
        """)
    int rebuildEffectiveRange(@Param("tenantId") Long tenantId,
                              @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按小时返回租户或用户汇总，较粗粒度由服务层再次上卷。 */
    @Select("""
      <script>SELECT bucket_start AS bucketStart,SUM(input_tokens) AS inputTokens,SUM(output_tokens) AS outputTokens,
      SUM(total_tokens) AS totalTokens,SUM(success_request_count) AS requestCount,
      SUM(estimated_cost_fen) AS estimatedCostFen,SUM(exact_usage_count) AS exactUsageCount,
      SUM(estimated_usage_count) AS estimatedUsageCount,SUM(unknown_usage_count) AS unknownUsageCount,
      SUM(calculated_count) AS calculatedCount,SUM(unpriced_count) AS unpricedCount,
      SUM(uncalculable_count) AS uncalculableCount,SUM(not_chargeable_count) AS notChargeableCount
      FROM t_token_usage_hourly_agg WHERE tenant_id=#{tenantId} AND bucket_start&gt;=#{from} AND bucket_start&lt;#{to}
      <if test='userId != null'>AND user_id=#{userId}</if>
      <if test='apiKeyId != null'>AND api_key_id=#{apiKeyId}</if>
      <if test='serviceId != null'>AND service_id=#{serviceId}</if>
      GROUP BY bucket_start ORDER BY bucket_start</script>
      """)
    List<UsageAggregateRow> summarizeHours(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                                           @Param("apiKeyId") Long apiKeyId, @Param("serviceId") Long serviceId,
                                           @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 首行是筛选范围的总计，后续是前 N 个分组，rowLimit 包含总计行。 */
    @Select("""
      <script>
      WITH breakdown_rows AS (
        SELECT
        <choose>
          <when test='dimension.name() == "MODEL"'>
            GROUPING(service_id) AS grand_total, service_id AS dimensionId, model
          </when>
          <when test='dimension.name() == "API_KEY"'>
            GROUPING(api_key_id) AS grand_total, api_key_id AS dimensionId, NULL AS model
          </when>
          <otherwise>GROUPING(user_id) AS grand_total, user_id AS dimensionId, NULL AS model</otherwise>
        </choose>,
        SUM(input_tokens) AS inputTokens, SUM(output_tokens) AS outputTokens, SUM(total_tokens) AS totalTokens,
        SUM(success_request_count) AS requestCount, SUM(estimated_cost_fen) AS estimatedCostFen,
        SUM(exact_usage_count) AS exactUsageCount, SUM(estimated_usage_count) AS estimatedUsageCount,
        SUM(unknown_usage_count) AS unknownUsageCount, SUM(calculated_count) AS calculatedCount,
        SUM(unpriced_count) AS unpricedCount, SUM(uncalculable_count) AS uncalculableCount,
        SUM(not_chargeable_count) AS notChargeableCount
        FROM t_token_usage_hourly_agg
        WHERE tenant_id=#{tenantId} AND bucket_start&gt;=#{from} AND bucket_start&lt;#{to}
        <if test='userId != null'>AND user_id=#{userId}</if>
        <if test='apiKeyId != null'>AND api_key_id=#{apiKeyId}</if>
        <if test='serviceId != null'>AND service_id=#{serviceId}</if>
        <choose>
          <when test='dimension.name() == "MODEL"'>
            GROUP BY service_id, model WITH ROLLUP
            HAVING GROUPING(service_id)=1 OR GROUPING(model)=0
          </when>
          <when test='dimension.name() == "API_KEY"'>GROUP BY api_key_id WITH ROLLUP</when>
          <otherwise>GROUP BY user_id WITH ROLLUP</otherwise>
        </choose>
      )
      SELECT dimensionId, model, inputTokens, outputTokens, totalTokens, requestCount, estimatedCostFen,
             exactUsageCount, estimatedUsageCount, unknownUsageCount, calculatedCount, unpricedCount,
             uncalculableCount, notChargeableCount
      FROM breakdown_rows
      ORDER BY grand_total DESC,
      <choose>
        <when test='order.name() == "ESTIMATED_COST_FEN"'>estimatedCostFen</when>
        <when test='order.name() == "REQUEST_COUNT"'>requestCount</when>
        <otherwise>totalTokens</otherwise>
      </choose> DESC, dimensionId IS NULL, dimensionId
      <if test='dimension.name() == "MODEL"'>, CAST(CONVERT(COALESCE(model, '') USING utf16) AS BINARY)</if>
      LIMIT #{rowLimit}
      </script>
      """)
    List<UsageBreakdownRow> summarizeRankedBreakdown(@Param("dimension") UsageBreakdownDimension dimension,
        @Param("order") UsageBreakdownOrder order, @Param("tenantId") Long tenantId, @Param("userId") Long userId,
        @Param("apiKeyId") Long apiKeyId, @Param("serviceId") Long serviceId,
        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("rowLimit") int rowLimit);
}
