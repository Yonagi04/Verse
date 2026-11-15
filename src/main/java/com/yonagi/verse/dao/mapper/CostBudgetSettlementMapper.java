package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.projection.EffectiveCostContribution;
import com.yonagi.verse.dao.projection.TenantCostBudgetAggregate;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CostBudgetSettlementMapper extends BaseMapper<CostBudgetSettlementDO> {
    /** 与既有epochMilli身份契约一致；只截断比较值，保留事实原精度；不可比较的历史日期明确拒绝。 */
    String SAME_REQUEST_START_MILLISECOND = """
            (TIMESTAMPADD(MICROSECOND,-MOD(MICROSECOND(i.request_started_at),1000),i.request_started_at)
            = TIMESTAMPADD(MICROSECOND,-MOD(MICROSECOND(s.request_started_at),1000),s.request_started_at)) IS TRUE
            """;

    /**
     * 在原调用的半开时间范围内读取最多 500 条有效费用事实。Key、模型及翻页游标可空。
     * 未应用的原始事实保留原标记。需要跨页保持一致时，调用方须在同一快照或预算锁内读取。
     */
    List<EffectiveCostContribution> pageEffectiveContributions(@Param("tenantId") Long tenantId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("keyId") Long keyId,
            @Param("serviceId") Long serviceId, @Param("after") EffectiveCostContribution.Cursor after);

    TenantCostBudgetAggregate aggregateEffective(@Param("tenantId") Long tenantId, @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end, @Param("keyId") Long keyId, @Param("serviceId") Long serviceId);

    @Select("SELECT * FROM t_cost_budget_settlement WHERE event_id=#{eventId} FOR UPDATE")
    CostBudgetSettlementDO lockEvent(@Param("eventId") String eventId);
    // 覆盖快照契约的两个合法来源，利用 tenant/source/request 唯一索引，避免按租户扫描并锁住整段账本。
    @Select("SELECT * FROM t_cost_budget_settlement FORCE INDEX (uk_budget_settlement_request) "
            + "WHERE tenant_id=#{tenantId} AND source IN ('API_KEY','PLAYGROUND') "
            + "AND request_id=#{requestId} LIMIT 1 FOR UPDATE")
    CostBudgetSettlementDO lockRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
    // 状态按字节匹配，与 Enum.valueOf 一致，不接受表排序规则容忍的大小写或尾空格。
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN CAST(cost_status AS BINARY) = _binary'CALCULATED'
                       THEN CAST(estimated_cost_fen AS DECIMAL(65,18)) ELSE 0 END), 0) AS used_cost_fen,
                   COUNT(CASE WHEN CAST(cost_status AS BINARY) = _binary'CALCULATED' THEN 1 END) AS calculated_count,
                   COUNT(CASE WHEN CAST(cost_status AS BINARY) = _binary'UNPRICED' THEN 1 END) AS unpriced_count,
                   COUNT(CASE WHEN CAST(cost_status AS BINARY) = _binary'UNCALCULABLE' THEN 1 END) AS uncalculable_count,
                   COUNT(CASE WHEN CAST(cost_status AS BINARY) = _binary'NOT_CHARGEABLE' THEN 1 END) AS not_chargeable_count,
                   COUNT(CASE WHEN cost_status IS NULL
                       OR CAST(cost_status AS BINARY) NOT IN
                           (_binary'CALCULATED', _binary'UNPRICED', _binary'UNCALCULABLE', _binary'NOT_CHARGEABLE')
                       OR (CAST(cost_status AS BINARY) = _binary'CALCULATED' AND estimated_cost_fen IS NULL)
                       THEN 1 END) AS invalid_count
            FROM t_cost_budget_settlement
            WHERE tenant_id=#{tenantId} AND budget_applied=1
              AND request_started_at >= #{start} AND request_started_at < #{end}
            """)
    TenantCostBudgetAggregate aggregateTenant(@Param("tenantId") Long tenantId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    @Select("SELECT * FROM t_cost_budget_settlement WHERE budget_applied=0 ORDER BY id LIMIT 100")
    List<CostBudgetSettlementDO> pending();

    @Select("""
            SELECT s.id,s.event_id,s.payload_hash,s.tenant_id,s.user_id,s.api_key_id,s.service_id,s.source,s.operation,
                   s.request_id,s.request_started_at,s.cost_status,s.estimated_cost_fen,s.currency,s.event_payload_json,
                   s.budget_applied,s.origin,s.create_time,s.applied_at
            FROM t_cost_budget_settlement s
            LEFT JOIN t_cost_budget_invocation i ON i.event_id=s.event_id
            WHERE s.budget_applied=0 AND (i.id IS NULL OR i.reconciliation_generation=0)
            ORDER BY s.id LIMIT 100
            """)
    List<CostBudgetSettlementDO> legacyPending();

    /** 原身份和正代次决定准入费用；CASE 尚无更正时仍须计入。 */
    @Select("""
            SELECT s.id,s.event_id,s.tenant_id,s.user_id,s.api_key_id,s.service_id,s.source,s.operation,
                   s.request_id,s.request_started_at,s.cost_status,s.estimated_cost_fen,s.budget_applied
            FROM t_cost_budget_settlement s
            JOIN t_cost_budget_invocation i ON i.event_id=s.event_id AND i.request_id=s.request_id
              AND i.tenant_id=s.tenant_id AND i.original_user_id=s.user_id
              AND i.api_key_id <=> s.api_key_id AND i.source=s.source AND
            """ + SAME_REQUEST_START_MILLISECOND + """
             WHERE s.tenant_id=#{tenantId} AND s.budget_applied=0 AND s.cost_status='CALCULATED'
              AND s.request_started_at>=#{from} AND s.request_started_at<#{until}
              AND i.reconciliation_generation>0
              AND NOT EXISTS(SELECT 1 FROM t_cost_reconciliation_case c
                JOIN t_cost_reconciliation_adjustment a ON a.case_id=c.id
                WHERE c.tenant_id=s.tenant_id AND c.request_id=s.request_id)
            ORDER BY s.request_started_at,s.id LIMIT 1001
            """)
    List<CostBudgetSettlementDO> pendingConfirmed(@Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM t_cost_budget_settlement s
              LEFT JOIN t_cost_budget_invocation i ON i.event_id=s.event_id
              WHERE s.tenant_id=#{tenantId} AND s.budget_applied=0 AND s.cost_status='CALCULATED'
                AND s.request_started_at>=#{from} AND s.request_started_at<#{until}
                AND (i.id IS NULL OR (i.reconciliation_generation>0 AND
                    (i.original_user_id IS NULL OR NOT (i.original_user_id <=> s.user_id)
                     OR i.tenant_id<>s.tenant_id OR i.request_id<>s.request_id
                     OR NOT (i.api_key_id <=> s.api_key_id) OR i.source<>s.source
                     OR NOT (
            """ + SAME_REQUEST_START_MILLISECOND + """
            )))))
            """)
    boolean hasInvalidPendingConfirmed(@Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);
    @Select("SELECT * FROM t_cost_budget_settlement WHERE tenant_id=#{tenantId} AND api_key_id=#{keyId} "
            + "AND request_started_at >= #{start} ORDER BY id")
    List<CostBudgetSettlementDO> history(@Param("tenantId") Long tenantId, @Param("keyId") Long keyId,
                                          @Param("start") LocalDateTime start);
    @Select("SELECT * FROM t_cost_budget_settlement WHERE tenant_id=#{tenantId} AND service_id=#{serviceId} "
            + "AND request_started_at >= #{start} ORDER BY id")
    List<CostBudgetSettlementDO> modelHistory(@Param("tenantId") Long tenantId,
            @Param("serviceId") Long serviceId, @Param("start") LocalDateTime start);
}
