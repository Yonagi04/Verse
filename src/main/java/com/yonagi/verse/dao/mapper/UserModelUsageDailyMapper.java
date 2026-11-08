package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.UserModelUsageDailyDO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

public interface UserModelUsageDailyMapper extends BaseMapper<UserModelUsageDailyDO> {
    @Insert("""
        INSERT INTO t_personal_usage_daily(user_id,usage_date,success_count,known_cost_fen,unknown_cost_count,updated_at)
        VALUES(#{userId},#{usageDate},#{successCount},#{knownCostFen},#{unknownCostCount},#{updatedAt})
        ON DUPLICATE KEY UPDATE success_count=success_count+VALUES(success_count),
          known_cost_fen=known_cost_fen+VALUES(known_cost_fen),unknown_cost_count=unknown_cost_count+VALUES(unknown_cost_count),updated_at=VALUES(updated_at)
        """)
    int contribute(UserModelUsageDailyDO contribution);

    @Select("""
        SELECT EXISTS (
            SELECT 1 FROM t_cost_reconciliation_case c JOIN t_cost_reconciliation_adjustment a ON a.case_id=c.id
            WHERE c.user_id=#{userId} AND c.request_started_at>=#{from} AND c.request_started_at<#{to}
        )
        """)
    boolean hasCorrections(@Param("userId") Long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Insert("""
        INSERT INTO t_personal_usage_daily(user_id,usage_date,success_count,known_cost_fen,unknown_cost_count,updated_at)
        SELECT #{userId},DATE(#{from}),COALESCE(SUM(f.successes),0),COALESCE(SUM(f.cost),0),
               COALESCE(SUM(f.unknown_cost),0),#{now}
        FROM (
          SELECT u.status='SUCCESS' AS successes,
                 CASE WHEN a.id IS NULL AND cost.cost_status='CALCULATED' THEN cost.estimated_cost_fen ELSE 0 END AS cost,
                 CASE WHEN a.id IS NULL AND (cost.cost_status IS NULL OR
                      cost.cost_status NOT IN ('CALCULATED','NOT_CHARGEABLE')) THEN 1 ELSE 0 END AS unknown_cost
          FROM t_token_usage u
          JOIN t_personal_usage_projection p ON p.user_id=u.user_id AND p.usage_id=u.id
            AND p.status='APPLIED' AND p.canonical_usage_id=u.id
          LEFT JOIN t_token_usage_cost cost ON cost.usage_id=u.id
          LEFT JOIN t_cost_reconciliation_case c ON c.event_id=u.event_id AND c.tenant_id=u.tenant_id
            AND c.user_id=u.user_id AND c.request_id=u.request_id AND c.source=u.source
          LEFT JOIN t_cost_reconciliation_adjustment a ON a.case_id=c.id AND a.version=(
            SELECT newest.version FROM t_cost_reconciliation_adjustment newest
            WHERE newest.case_id=c.id ORDER BY newest.version DESC LIMIT 1
          )
          WHERE u.user_id=#{userId} AND u.request_started_at>=#{from} AND u.request_started_at<#{to}
          UNION ALL
          SELECT 0,CASE WHEN a.cost_status='CALCULATED' THEN a.cost_fen ELSE 0 END,
                 a.cost_status NOT IN ('CALCULATED','NOT_CHARGEABLE')
          FROM t_cost_reconciliation_case c
          JOIN t_cost_reconciliation_adjustment a ON a.case_id=c.id AND a.version=(
            SELECT newest.version FROM t_cost_reconciliation_adjustment newest
            WHERE newest.case_id=c.id ORDER BY newest.version DESC LIMIT 1
          )
          WHERE c.user_id=#{userId} AND c.request_started_at>=#{from} AND c.request_started_at<#{to}
        ) f
        ON DUPLICATE KEY UPDATE success_count=VALUES(success_count),known_cost_fen=VALUES(known_cost_fen),
          unknown_cost_count=VALUES(unknown_cost_count),updated_at=VALUES(updated_at)
        """)
    int rebuildEffectiveDay(@Param("userId") Long userId, @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to, @Param("now") LocalDateTime now);
}
