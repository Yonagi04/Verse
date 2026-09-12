package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import com.yonagi.verse.dao.projection.UsageRawExportRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 消耗 Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsageDO> {

    @Select("SELECT COUNT(*) FROM t_token_usage WHERE event_id=#{eventId}")
    long countByEventId(@Param("eventId") String eventId);

    /** 按稳定事件 ID 查询用量事实主键。 */
    @Select("SELECT id FROM t_token_usage WHERE event_id=#{eventId} LIMIT 1")
    Long selectIdByEventId(@Param("eventId") String eventId);

    /** 统计授权筛选范围内可导出的逐请求行数。 */
    @Select("""
        <script>
        SELECT COUNT(*) FROM t_token_usage u
        WHERE u.tenant_id=#{tenantId}
          AND COALESCE(u.request_started_at,u.create_time) &gt;= #{from}
          AND COALESCE(u.request_started_at,u.create_time) &lt; #{to}
        <if test='userId != null'>AND u.user_id=#{userId}</if>
        <if test='apiKeyId != null'>AND u.api_key_id=#{apiKeyId}</if>
        <if test='serviceId != null'>AND u.service_id=#{serviceId}</if>
        </script>
        """)
    long countRawExport(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                        @Param("apiKeyId") Long apiKeyId, @Param("serviceId") Long serviceId,
                        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按请求时间和主键游标分批读取逐请求导出数据。 */
    @Select("""
        <script>
        SELECT u.id,
               COALESCE(u.request_started_at,u.create_time) AS request_started_at,
               u.request_id,
               COALESCE(NULLIF(usr.nickname,''),usr.username,CONCAT('成员 ',u.user_id)) AS member_label,
               COALESCE(CONCAT(k.name,' (',k.key_prefix,')'),CONCAT('API Key ',u.api_key_id)) AS api_key_label,
               COALESCE(s.name,CONCAT('服务 ',u.service_id)) AS service_label,
               u.model,u.status,u.usage_source,
               COALESCE(u.input_tokens,u.prompt_tokens,0) AS input_tokens,
               COALESCE(u.output_tokens,u.completion_tokens,0) AS output_tokens,
               COALESCE(u.normalized_total_tokens,u.total_tokens,0) AS total_tokens,
               COALESCE(c.cost_status,u.cost_status) AS cost_status,
               COALESCE(c.estimated_cost_fen,u.estimated_cost_fen) AS estimated_cost_fen,
               COALESCE(c.currency,u.currency) AS currency
        FROM t_token_usage u
        LEFT JOIN t_token_usage_cost c ON c.usage_id=u.id
        LEFT JOIN t_user usr ON usr.user_id=u.user_id
        LEFT JOIN t_api_key k ON k.api_key_id=u.api_key_id AND k.tenant_id=u.tenant_id
        LEFT JOIN t_llm_service s ON s.service_id=u.service_id AND s.tenant_id=u.tenant_id
        WHERE u.tenant_id=#{tenantId}
          AND COALESCE(u.request_started_at,u.create_time) &gt;= #{from}
          AND COALESCE(u.request_started_at,u.create_time) &lt; #{to}
        <if test='userId != null'>AND u.user_id=#{userId}</if>
        <if test='apiKeyId != null'>AND u.api_key_id=#{apiKeyId}</if>
        <if test='serviceId != null'>AND u.service_id=#{serviceId}</if>
        <if test='afterTime != null'>
          AND (COALESCE(u.request_started_at,u.create_time) &gt; #{afterTime}
            OR (COALESCE(u.request_started_at,u.create_time)=#{afterTime} AND u.id &gt; #{afterId}))
        </if>
        ORDER BY COALESCE(u.request_started_at,u.create_time),u.id
        LIMIT #{limit}
        </script>
        """)
    List<UsageRawExportRow> selectRawExportBatch(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                                                 @Param("apiKeyId") Long apiKeyId, @Param("serviceId") Long serviceId,
                                                 @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                 @Param("afterTime") LocalDateTime afterTime, @Param("afterId") Long afterId,
                                                 @Param("limit") int limit);
}
