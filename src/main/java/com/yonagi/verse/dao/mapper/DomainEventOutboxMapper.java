package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/** 通用可靠领域事件 Outbox Mapper。 */
public interface DomainEventOutboxMapper extends BaseMapper<DomainEventOutboxDO> {
    @Select("""
            SELECT * FROM t_domain_event_outbox
            WHERE ((status IN ('PENDING','RETRY') AND next_retry_at <= #{now})
                OR (status='CLAIMED' AND claim_expires_at <= #{now}))
            ORDER BY next_retry_at,id LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<DomainEventOutboxDO> lockClaimable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            <script>UPDATE t_domain_event_outbox SET status='CLAIMED',claim_owner=#{owner},
            claim_expires_at=#{expiresAt},update_time=#{now} WHERE id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
            """)
    int claim(@Param("ids") List<Long> ids, @Param("owner") String owner,
              @Param("expiresAt") LocalDateTime expiresAt, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_domain_event_outbox SET status='PUBLISHED',attempt_count=attempt_count+1,
            published_at=#{now},claim_owner=NULL,claim_expires_at=NULL,last_error=NULL,update_time=#{now}
            WHERE id=#{id} AND status='CLAIMED' AND claim_owner=#{owner}
            """)
    int markPublished(@Param("id") Long id, @Param("owner") String owner, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_domain_event_outbox SET status=#{status},attempt_count=attempt_count+1,
            next_retry_at=#{retryAt},claim_owner=NULL,claim_expires_at=NULL,last_error=#{error},update_time=#{now}
            WHERE id=#{id} AND status='CLAIMED' AND claim_owner=#{owner}
            """)
    int markPublishFailure(@Param("id") Long id, @Param("owner") String owner, @Param("status") String status,
                           @Param("retryAt") LocalDateTime retryAt, @Param("error") String error,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_domain_event_outbox o JOIN t_tenant_activity_log a ON a.event_id=o.event_id
            SET o.reconciled_at=COALESCE(o.reconciled_at,#{now}),o.update_time=#{now}
            WHERE o.event_type='TENANT_ACTIVITY' AND o.status='PUBLISHED' AND o.reconciled_at IS NULL
            """)
    int reconcilePublished(@Param("now") LocalDateTime now);

    @Delete("DELETE FROM t_domain_event_outbox WHERE status='PUBLISHED' AND reconciled_at IS NOT NULL AND reconciled_at < #{cutoff}")
    int deleteReconciledBefore(@Param("cutoff") LocalDateTime cutoff);

    @Update("""
            UPDATE t_domain_event_outbox SET status='FAILED',last_error=#{error},claim_owner=NULL,
            claim_expires_at=NULL,update_time=#{now} WHERE event_id=#{eventId}
            """)
    int markConsumerDlq(@Param("eventId") String eventId, @Param("error") String error, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_domain_event_outbox SET status='RETRY',attempt_count=0,next_retry_at=#{now},claim_owner=NULL,
            claim_expires_at=NULL,last_error=NULL,update_time=#{now}
            WHERE event_id=#{eventId} AND tenant_id=#{tenantId} AND status IN ('FAILED','PUBLISHED')
              AND NOT EXISTS (SELECT 1 FROM t_tenant_activity_log a WHERE a.event_id=#{eventId})
            """)
    int resetForReplay(@Param("tenantId") Long tenantId, @Param("eventId") String eventId, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM t_domain_event_outbox WHERE status=#{status}")
    long countStatus(@Param("status") String status);

    @Select("SELECT MIN(create_time) FROM t_domain_event_outbox WHERE status IN ('PENDING','RETRY','CLAIMED','FAILED')")
    LocalDateTime oldestUnfinishedAt();

    @Select("""
            SELECT COUNT(*) FROM t_domain_event_outbox o LEFT JOIN t_tenant_activity_log a ON a.event_id=o.event_id
            WHERE o.event_type='TENANT_ACTIVITY' AND o.status='PUBLISHED' AND o.published_at < #{threshold} AND a.event_id IS NULL
            """)
    long countPublishedMissingFacts(@Param("threshold") LocalDateTime threshold);
}
