package com.yonagi.verse.dao.mapper;

import org.apache.ibatis.annotations.Delete;

/** 费用预警及本人通知的分批清理。 */
public interface UserCostAlertCleanupMapper {
    @Delete("DELETE FROM t_personal_cost_alert_delivery WHERE recipient_user_id=#{userId} LIMIT 500") int deliveries(Long userId);
    @Delete("DELETE FROM t_personal_cost_alert_event WHERE user_id=#{userId} LIMIT 500") int events(Long userId);
    @Delete("DELETE FROM t_personal_cost_alert_config WHERE user_id=#{userId}") int config(Long userId);
    /** 仅本人系统通知正文，先清正文再分批清关系；其他收件人的通知不受影响。 */
    @Delete("""
        DELETE FROM t_notification WHERE notification_id IN
          (SELECT e.notification_id FROM t_personal_cost_alert_event e WHERE e.user_id=#{userId}) AND tenant_id IS NULL
        LIMIT 500
        """)
    int deletePersonalNotifications(Long userId);

    @Delete("""
        DELETE FROM t_notification_recipient WHERE notification_id IN
          (SELECT e.notification_id FROM t_personal_cost_alert_event e WHERE e.user_id=#{userId}) AND user_id=#{userId}
        LIMIT 500
        """)
    int deletePersonalRecipients(Long userId);

}
