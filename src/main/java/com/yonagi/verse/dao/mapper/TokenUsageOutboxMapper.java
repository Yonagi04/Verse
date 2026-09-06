package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TokenUsageOutboxDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 计费用量 Outbox Mapper。 */
@Mapper
public interface TokenUsageOutboxMapper extends BaseMapper<TokenUsageOutboxDO> {
    @Select("""
            SELECT * FROM t_token_usage_outbox
            WHERE ((status IN ('PENDING','RETRY') AND next_retry_at <= #{now})
                OR (status = 'CLAIMED' AND claim_expires_at <= #{now}))
            ORDER BY next_retry_at, id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<TokenUsageOutboxDO> lockClaimable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            <script>
            UPDATE t_token_usage_outbox
            SET status='CLAIMED', claim_owner=#{owner}, claim_expires_at=#{expiresAt}, update_time=#{now}
            WHERE id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            </script>
            """)
    int claim(@Param("ids") List<Long> ids, @Param("owner") String owner,
              @Param("expiresAt") LocalDateTime expiresAt, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_token_usage_outbox
            SET status='PUBLISHED', attempt_count=attempt_count+1, published_at=#{now},
                claim_owner=NULL, claim_expires_at=NULL, last_error=NULL, update_time=#{now}
            WHERE id=#{id} AND status='CLAIMED' AND claim_owner=#{owner}
            """)
    int markPublished(@Param("id") Long id, @Param("owner") String owner, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_token_usage_outbox
            SET status=#{status}, attempt_count=attempt_count+1, next_retry_at=#{nextRetryAt},
                claim_owner=NULL, claim_expires_at=NULL, last_error=#{lastError}, update_time=#{now}
            WHERE id=#{id} AND status='CLAIMED' AND claim_owner=#{owner}
            """)
    int markPublishFailure(@Param("id") Long id, @Param("owner") String owner,
                           @Param("status") String status, @Param("nextRetryAt") LocalDateTime nextRetryAt,
                           @Param("lastError") String lastError, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_token_usage_outbox o
            JOIN t_token_usage u ON u.event_id=o.event_id
            SET o.reconciled_at=COALESCE(o.reconciled_at, #{now}), o.update_time=#{now}
            WHERE o.status='PUBLISHED' AND o.reconciled_at IS NULL
            """)
    int reconcilePublished(@Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM t_token_usage_outbox
            WHERE status='PUBLISHED' AND reconciled_at IS NOT NULL AND reconciled_at < #{cutoff}
            """)
    int deleteReconciledBefore(@Param("cutoff") LocalDateTime cutoff);

    @Update("""
            UPDATE t_token_usage_outbox
            SET status='RETRY', attempt_count=0, next_retry_at=#{now}, claim_owner=NULL,
                claim_expires_at=NULL, last_error=NULL, update_time=#{now}
            WHERE event_id=#{eventId} AND tenant_id=#{tenantId} AND status IN ('FAILED','PUBLISHED')
              AND NOT EXISTS (SELECT 1 FROM t_token_usage u WHERE u.event_id=#{eventId})
            """)
    int resetForReplay(@Param("tenantId") Long tenantId, @Param("eventId") String eventId,
                       @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_token_usage_outbox
            SET status='FAILED', last_error=#{error}, claim_owner=NULL, claim_expires_at=NULL,
                update_time=#{now}
            WHERE event_id=#{eventId}
            """)
    int markConsumerDlq(@Param("eventId") String eventId, @Param("error") String error,
                        @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM t_token_usage_outbox WHERE status=#{status}")
    long countStatus(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM t_token_usage_outbox WHERE tenant_id=#{tenantId} AND status=#{status}")
    long countTenantStatus(@Param("tenantId") Long tenantId, @Param("status") String status);

    @Select("""
            SELECT COUNT(*) FROM t_token_usage_outbox o
            LEFT JOIN t_token_usage u ON u.event_id=o.event_id
            WHERE o.status='PUBLISHED' AND u.event_id IS NULL
            """)
    long countPublishedMissingFacts();

    @Select("""
            SELECT COUNT(*) FROM t_token_usage_outbox o
            LEFT JOIN t_token_usage u ON u.event_id=o.event_id
            WHERE o.tenant_id=#{tenantId} AND o.status='PUBLISHED' AND u.event_id IS NULL
            """)
    long countTenantPublishedMissingFacts(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT MIN(create_time) FROM t_token_usage_outbox
            WHERE status IN ('PENDING','RETRY','CLAIMED','FAILED')
            """)
    LocalDateTime oldestUnfinishedAt();
}
