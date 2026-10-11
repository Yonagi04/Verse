package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.LlmCostAlertDeliveryDO;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LlmCostAlertDeliveryMapper extends BaseMapper<LlmCostAlertDeliveryDO> {
    /** 用成员当前读生成固定收件集合，联系方式已经由用户领域加密。 */
    @Insert("""
        INSERT INTO t_llm_cost_alert_delivery(event_id,channel,recipient_user_id,contact_hash,contact_encrypted,
          request_id,status,reason_code,attempt_count,next_retry_at,create_time,update_time)
        SELECT #{eventId},#{channel},u.user_id,
          CASE #{channel} WHEN 'SMS' THEN u.phone_hash WHEN 'EMAIL' THEN u.email_hash ELSE NULL END,
          CASE #{channel} WHEN 'SMS' THEN u.phone WHEN 'EMAIL' THEN u.email ELSE NULL END,
          SHA2(CONCAT(#{eventId},':',#{channel},':',u.user_id),256),
          CASE WHEN (#{channel}='SMS' AND (u.phone IS NULL OR u.phone='')) OR
                    (#{channel}='EMAIL' AND (u.email IS NULL OR u.email='')) THEN 'SKIPPED' ELSE 'PENDING' END,
          CASE WHEN (#{channel}='SMS' AND (u.phone IS NULL OR u.phone='')) OR
                    (#{channel}='EMAIL' AND (u.email IS NULL OR u.email='')) THEN 'SKIPPED_NO_CONTACT' ELSE NULL END,
          0,#{now},#{now},#{now}
        FROM t_user u JOIN t_user_tenant m ON m.user_id=u.user_id
        WHERE m.tenant_id=#{tenantId} AND m.left_at IS NULL AND u.status=1 AND u.del_flag=0
          AND (#{channel}='IN_APP' OR m.role IN ('ADMIN','SUPER_ADMIN'))
        """)
    int snapshotRecipients(@Param("eventId") String eventId, @Param("tenantId") Long tenantId,
            @Param("channel") String channel, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE t_llm_cost_alert_delivery d JOIN
          (SELECT * FROM (SELECT contact_hash,MIN(id) canonical FROM t_llm_cost_alert_delivery
           WHERE event_id=#{eventId} AND channel=#{channel} AND status='PENDING' AND contact_hash IS NOT NULL
           GROUP BY contact_hash) grouped) c ON c.contact_hash=d.contact_hash
        SET d.dispatch_contact_hash=CASE WHEN d.id=c.canonical THEN d.contact_hash ELSE NULL END,
            d.canonical_delivery_id=CASE WHEN d.id=c.canonical THEN NULL ELSE c.canonical END,
            d.status=CASE WHEN d.id=c.canonical THEN 'PENDING' ELSE 'SKIPPED' END,
            d.reason_code=CASE WHEN d.id=c.canonical THEN NULL ELSE 'SKIPPED_DUPLICATE_CONTACT' END
        WHERE d.event_id=#{eventId} AND d.channel=#{channel}
        """)
    void deduplicateContacts(@Param("eventId") String eventId, @Param("channel") String channel);

    /** 固定本轮上界，持续新增任务不能推迟对队首的回访。 */
    @Select("SELECT COALESCE(MAX(id),0) FROM t_llm_cost_alert_delivery")
    long maxId();

    @Select("SELECT * FROM t_llm_cost_alert_delivery WHERE id>#{afterId} AND id<=#{throughId} AND ("
            + "(status IN ('PENDING','RETRY_WAIT') AND next_retry_at<=#{now}) "
            + "OR (status='CLAIMED' AND lease_until<#{now}) OR status='QUEUED') ORDER BY id LIMIT 100")
    List<LlmCostAlertDeliveryDO> pending(@Param("now") LocalDateTime now,
            @Param("afterId") long afterId, @Param("throughId") long throughId);
    @Select("SELECT * FROM t_llm_cost_alert_delivery WHERE id=#{id} FOR UPDATE")
    LlmCostAlertDeliveryDO lock(@Param("id") Long id);
}
