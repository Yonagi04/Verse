package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yonagi.verse.common.database.BaseDO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 21:25
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_notification")
public class NotificationDO extends BaseDO {

    // 自增主键
    private Long id;

    // 雪花业务ID
    private Long notificationId;

    // 产生通知的租户ID
    private Long tenantId;

    // SYSTEM / ANNOUNCEMENT
    private String type;

    // INFO / WARNING / CRITICAL
    private String severity;

    // 通知标题
    private String title;

    // 通知正文
    private String content;

    // 发送者（系统通知为NULL）
    private Long senderId;
}
