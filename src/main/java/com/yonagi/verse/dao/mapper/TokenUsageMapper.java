package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import com.yonagi.verse.dao.entity.UsageCostAggregateDO;
import com.yonagi.verse.dao.mapper.sql.TokenUsageSqlProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

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

    @Insert("""
            INSERT INTO t_token_usage (
                user_id, tenant_id, api_key_id, service_id, model,
                prompt_tokens, completion_tokens, total_tokens, request_id, status, usage_source,
                event_id, request_started_at, input_tokens, cached_input_tokens,
                cache_write_input_tokens, output_tokens, pricing_id, billing_mode, price_period_type,
                price_period_id, cache_miss_input_price_fen, cache_hit_input_price_fen,
                output_price_fen, request_price_fen, estimated_cost_fen, cost_status,
                currency, usage_details_json, create_time
            ) VALUES (
                #{userId}, #{tenantId}, #{apiKeyId}, #{serviceId}, #{model},
                #{promptTokens}, #{completionTokens}, #{totalTokens}, #{requestId}, #{status}, #{usageSource},
                #{eventId}, #{requestStartedAt}, #{inputTokens}, #{cachedInputTokens},
                #{cacheWriteInputTokens}, #{outputTokens}, #{pricingId}, #{billingMode}, #{pricePeriodType},
                #{pricePeriodId}, #{cacheMissInputPriceFen}, #{cacheHitInputPriceFen},
                #{outputPriceFen}, #{requestPriceFen}, #{estimatedCostFen}, #{costStatus},
                #{currency}, CAST(#{usageDetailsJson} AS JSON), NOW(3)
            )
            ON DUPLICATE KEY UPDATE event_id = event_id
            """)
    int insertIdempotently(TokenUsageDO tokenUsage);

    @SelectProvider(type = TokenUsageSqlProvider.class, method = "aggregate")
    UsageCostAggregateDO aggregateCost(@Param("tenantId") Long tenantId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       @Param("serviceId") Long serviceId,
                                       @Param("apiKeyId") Long apiKeyId,
                                       @Param("userId") Long userId);

    @SelectProvider(type = TokenUsageSqlProvider.class, method = "aggregateTimeseries")
    List<UsageCostAggregateDO> aggregateCostTimeseries(@Param("tenantId") Long tenantId,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to,
                                                       @Param("serviceId") Long serviceId,
                                                       @Param("apiKeyId") Long apiKeyId,
                                                       @Param("userId") Long userId,
                                                       @Param("granularity") String granularity);

    @SelectProvider(type = TokenUsageSqlProvider.class, method = "aggregateByDimension")
    List<UsageCostAggregateDO> aggregateCostByDimension(@Param("tenantId") Long tenantId,
                                                        @Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to,
                                                        @Param("serviceId") Long serviceId,
                                                        @Param("apiKeyId") Long apiKeyId,
                                                        @Param("userId") Long userId,
                                                        @Param("dimension") String dimension);
}
