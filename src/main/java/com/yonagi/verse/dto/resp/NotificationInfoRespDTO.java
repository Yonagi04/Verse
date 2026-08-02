package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/02 10:50
 */
@Data
public class NotificationInfoRespDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long notificationId;

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

    // 通知创建时间
    private Date createTime;
}
