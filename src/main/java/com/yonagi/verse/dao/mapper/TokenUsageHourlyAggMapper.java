package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TokenUsageHourlyAggDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
import com.yonagi.verse.dao.projection.UsageAggregateRow;
import org.apache.ibatis.annotations.Select;

/** Token 用量小时预聚合 Mapper。 */
@Mapper
public interface TokenUsageHourlyAggMapper extends BaseMapper<TokenUsageHourlyAggDO> {
    /** 删除待重算时间窗，随后写入绝对聚合值。 */
    @Delete("DELETE FROM t_token_usage_hourly_agg WHERE bucket_start>=#{from} AND bucket_start<#{to}")
    int deleteRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

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
        WHERE COALESCE(u.request_started_at,u.create_time)>=#{from}
          AND COALESCE(u.request_started_at,u.create_time)<#{to}
        GROUP BY u.tenant_id,u.user_id,u.api_key_id,u.service_id,u.model,
          DATE_FORMAT(COALESCE(u.request_started_at,u.create_time),'%Y-%m-%d %H:00:00')
        """)
    int rebuildRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

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
      GROUP BY bucket_start ORDER BY bucket_start</script>
      """)
    List<UsageAggregateRow> summarizeHours(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                                           @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
