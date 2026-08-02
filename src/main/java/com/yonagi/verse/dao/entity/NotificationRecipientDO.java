package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 21:29
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_notification_recipient")
public class NotificationRecipientDO {

    private Long id;

    // 接收用户ID
    private Long userId;

    // 通知业务ID
    private Long notificationId;

    // 0=未读, 1=已读
    private Integer isRead;

    private Date readTime;

    private Date createTime;
}
